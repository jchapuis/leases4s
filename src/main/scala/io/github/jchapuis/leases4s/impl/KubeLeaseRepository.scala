package io.github.jchapuis.leases4s.impl

import cats.{Applicative, MonadError}
import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.{Async, Clock, Resource}
import cats.effect.std.Random
import cats.effect.syntax.resource.*
import cats.effect.syntax.spawn.*
import cats.effect.syntax.temporal.*
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import com.goyeau.kubernetes.client.{KubernetesClient, WatchEvent}
import fs2.concurrent.Topic
import io.github.jchapuis.leases4s.model.*
import io.github.jchapuis.leases4s.LeaseRepository.{LeaseEvent, LeaseParameters}
import io.github.jchapuis.leases4s.impl.AutoUpdatingLeaseMap.LeaseMap
import io.github.jchapuis.leases4s.impl.K8sHelpers.*
import io.github.jchapuis.leases4s.impl.model.LeaseData
import io.github.jchapuis.leases4s.{HeldLease, Lease, LeaseRepository}
import io.k8s.api.coordination.v1
import io.k8s.apimachinery.pkg.apis.meta.v1.{DeleteOptions, Preconditions}
import org.typelevel.log4cats.Logger
import RetryHelpers.*
import scala.concurrent.duration.*

private[impl] class KubeLeaseRepository[F[_]: Async: Random: Logger](
    val labels: List[Label],
    leaseMap: LeaseMap[F],
    eventsTopic: Topic[F, LeaseEvent[F]],
    leaseAcquiredTopic: Topic[F, KubeLease[F]]
)(implicit parameters: LeaseParameters, namespace: Namespace = Namespace.Default, client: KubernetesClient[F])
    extends LeaseRepository[F] {
  private val leases = client.leases.namespace(namespace)
  private lazy val unit = Applicative[F].unit

  def acquire(id: LeaseID, holder: HolderID)(implicit parameters: LeaseParameters): Resource[F, HeldLease[F]] =
    for {
      lease <- Resource.makeFull((poll: Poll[F]) =>
        poll(
          acquireLease(id, holder).retryWithBackoff(
            throwable =>
              Logger[F].warn(show"Failed to acquire lease $id for $holder: ${throwable.getMessage}, retrying..."),
            parameters.baseOnErrorRetryDelay
          )
        )
      )(
        delete(_).handleErrorWith(error =>
          Logger[F].warn(error)(show"Failed to delete lease $id upon release, leaving it up for expiry...")
        )
      )
      _ <- Resource.makeFull((poll: Poll[F]) => poll(scheduleLeaseRenewal(lease, holder).start))(_.cancel)
    } yield lease

  private def scheduleLeaseRenewal(lease: KubeLease[F], holderID: HolderID): F[Unit] =
    (for {
      _ <- Logger[F].debug(show"Scheduling lease ${lease.id} renewal for $holderID before expiration")
      _ <- Temporal[F].sleep(parameters.leaseDuration * parameters.renewalFrequencyRatio)
      _ <- lease.holder
        .map(_ === holderID)
        .ifM(
          ifTrue = unit,
          ifFalse = new RuntimeException(show"Lease ${lease.id} is no longer held by $holderID").raiseError
        )
      _ <- Logger[F].debug(show"Renewing lease ${lease.id}")
      _ <- renewLease(lease).retryWithBackoff(
        throwable =>
          Logger[F].warn(show"Failed to renew lease ${lease.id} for $holderID: ${throwable.getMessage}, retrying..."),
        parameters.baseOnErrorRetryDelay
      )
      _ <- Logger[F].debug(show"Renewed lease ${lease.id}")
      _ <- scheduleLeaseRenewal(lease, holderID)
    } yield ()).handleErrorWith { throwable =>
      Logger[F].error(show"Definitive failure in renewing lease ${lease.id}: ${throwable.getMessage}, giving up...")
    }

  private def renewLease(lease: KubeLease[F]) =
    for {
      now <- Clock[F].realTimeInstant
      data <- lease.data.get
      renewedData = data.copy(duration = parameters.leaseDuration, lastRenewTime = Some(now))
      status <- leases.createOrUpdate(k8sLeaseFromData(lease.id, renewedData))
      _ <- Applicative[F].unlessA(status.isSuccess)(
        MonadError[F, Throwable].raiseError(new RuntimeException(status.sanitizedReason))
      )
    } yield ()

  private def acquireLease(id: LeaseID, holder: HolderID): F[KubeLease[F]] = {
    for {
      maybeExistingLease <- getLease(id)
      heldLease <- maybeExistingLease.map(claimUntilHeld(_, holder)).getOrElse(createLease(id, holder))
      _ <- Logger[F].info(show"Acquired lease $id for $holder")
    } yield heldLease
  }

  private def claimUntilHeld(existingLease: KubeLease[F], claimant: HolderID): F[KubeLease[F]] =
    existingLease.holder
      .map(_ === claimant)
      .ifM(
        ifTrue = for {
          _ <- Logger[F]
            .debug(show"Lease ${existingLease.id} is (or was) already held by $claimant, renewing it")
          _ <- renewLease(existingLease)
        } yield existingLease,
        ifFalse = for {
          existingHolder <- existingLease.holder
          _ <- Logger[F]
            .debug(show"Lease ${existingLease.id} already held by $existingHolder, waiting for it to expire")
          _ <- existingLease.expired.compile.drain
          _ <- Logger[F]
            .debug(
              show"Lease ${existingLease.id} that was held by $existingHolder has expired, cleaning it up and attempting to recreate it"
            )
          _ <- existingLease.data.get.map(_.deleted).ifF(unit, delete(existingLease))
          lease <- createLease(existingLease.id, claimant)
        } yield lease
      )

  private def createLease(id: LeaseID, holderID: HolderID): F[KubeLease[F]] =
    (for {
      _ <- OptionT.liftF(Logger[F].debug(show"Attempting to create lease $id for $holderID"))
      deferredCreatedLease <- OptionT.liftF(Deferred[F, Option[KubeLease[F]]])
      _ <- OptionT.liftF(nextAcquiredLeaseFor(id, holderID).flatTap(deferredCreatedLease.complete).start)
      now <- OptionT.liftF(Clock[F].realTimeInstant)
      status <- OptionT.liftF(leases.createOrUpdate(k8sLease(id, holderID, labels, now, parameters.leaseDuration)))
      _ <- OptionT.liftF(
        if (!status.isSuccess) Logger[F].warn(show"Failed to create lease $id for $holderID: ${status.reason}")
        else ().pure[F]
      )
      _ <- OptionT.whenF(status.isSuccess)(Logger[F].debug(show"Created lease $id for $holderID"))
      lease <- OptionT(deferredCreatedLease.get)
    } yield lease).getOrRaise(new RuntimeException(show"Failed to create lease $id for $holderID"))

  private def nextAcquiredLeaseFor(id: LeaseID, holderID: HolderID): F[Option[KubeLease[F]]] =
    leaseAcquiredTopic.subscribeUnbounded
      .filter(_.id === id)
      .evalFilter(_.holder.map(_ === holderID))
      .head
      .compile
      .last

  private def delete(lease: KubeLease[F]) =
    for {
      _ <- Logger[F].debug(show"Deleting lease ${lease.id}")
      version <- lease.version
      status <- leases.delete(
        lease.id,
        Some(DeleteOptions(preconditions = Some(Preconditions(resourceVersion = Some(version)))))
      )
      _ <-
        if (status.isSuccess) Logger[F].debug(show"Deleted lease ${lease.id}")
        else
          Logger[F].warn(show"Failed to delete lease ${lease.id}: ${status.reason}") >> MonadError[F, Throwable]
            .raiseError(new RuntimeException(status.reason))
    } yield ()

  private def getLease(id: LeaseID) = OptionT(leaseMap.get.map(_.get(id))).value

  def get(id: LeaseID): F[Option[Lease[F]]] = OptionT(getLease(id)).map(lease => lease: Lease[F]).value

  def list: F[List[Lease[F]]] = leaseMap.get.map(_.values.map(lease => lease: Lease[F]).toList)

  def watcher: fs2.Stream[F, LeaseEvent[F]] = eventsTopic.subscribeUnbounded
}

object KubeLeaseRepository {
  def apply[F[_]: Async: Logger](label: Label, others: Label*)(implicit
      client: KubernetesClient[F],
      namespace: Namespace,
      parameters: LeaseParameters
  ): Resource[F, LeaseRepository[F]] = {
    val labels = label :: others.toList
    val labelMap = labels.map(l => l.key.value -> l.value.value).toMap
    val leases = client.leases.namespace(namespace)
    Random.scalaUtilRandom[F].toResource.flatMap { implicit random: Random[F] =>
      for {
        leaseList <- leases.list(labelMap).toResource
        topics <- Topics.resource
        leaseMap <- AutoUpdatingLeaseMap(leaseList, topics)
        listRevision = leaseList.metadata.flatMap(_.resourceVersion)
        eventsStream = streamFrom(leases.watch(labelMap, listRevision))
        _ <- Resource.make(eventsStream.broadcastThrough(topics.watcher.publish).compile.drain.start)(_.cancel)
      } yield new KubeLeaseRepository[F](labels, leaseMap, topics.events, topics.leaseAcquired)
    }
  }

  private def streamFrom[F[_]: Async: Logger](stream: fs2.Stream[F, Either[String, WatchEvent[v1.Lease]]]) = stream
    .evalTap(
      _.fold(
        error => Logger[F].error(show"Error watching leases: $error"),
        event =>
          Logger[F].debug(
            s"Received event for ${event.`object`.metadata.flatMap(_.name).getOrElse("unknown")}: ${event.`type`.toString}"
          )
      )
    )
    .map(_.toOption.flatMap(leaseEventFromV1))
    .collect { case Some(event) => event }

}
