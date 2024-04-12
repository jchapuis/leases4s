package io.github.jchapuis.leases4s.impl

import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.{Async, Clock, Resource}
import cats.effect.std.Random
import cats.effect.syntax.resource.*
import cats.effect.syntax.spawn.*
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import cats.{Applicative, MonadError}
import com.goyeau.kubernetes.client.{EventType, KubernetesClient, WatchEvent}
import fs2.concurrent.Topic
import io.github.jchapuis.leases4s.LeaseRepository.{LeaseEvent, LeaseParameters}
import io.github.jchapuis.leases4s.impl.AutoUpdatingLeaseMap.LeaseMap
import io.github.jchapuis.leases4s.impl.K8sHelpers.*
import io.github.jchapuis.leases4s.impl.RetryHelpers.*
import io.github.jchapuis.leases4s.model.*
import io.github.jchapuis.leases4s.{HeldLease, Lease, LeaseRepository}
import io.k8s.api.coordination.v1
import org.typelevel.log4cats.Logger

private[impl] class KubeLeaseRepository[F[_]: Async: Random: Logger](
    val labels: List[Label],
    leaseMap: LeaseMap[F],
    eventsTopic: Topic[F, LeaseEvent[F]],
    leaseAcquiredTopic: Topic[F, KubeLease[F]]
)(implicit parameters: LeaseParameters, kubeApi: KubeApi[F])
    extends LeaseRepository[F] {
  private val api       = kubeApi.leasesApi
  private lazy val unit = Applicative[F].unit

  def acquire(id: LeaseID, holder: HolderID, annotations: List[Annotation])(implicit
      parameters: LeaseParameters
  ): Resource[F, HeldLease[F]] =
    for {
      lease <- Resource.makeFull((poll: Poll[F]) =>
        poll(
          acquireLease(id, holder, annotations).retryWithBackoff(
            throwable =>
              Logger[F].warn(show"Failed to acquire lease $id for $holder: ${throwable.getMessage}, retrying..."),
            parameters.initialOnErrorRetryDelay
          )
        )
      )(
        _.delete.handleErrorWith(error =>
          Logger[F].warn(error)(show"Failed to delete lease $id upon release, leaving it up for expiry...")
        )
      )
      _ <- scheduleLeaseRenewal(lease, holder).background
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
        parameters.initialOnErrorRetryDelay
      )
      _ <- Logger[F].debug(show"Renewed lease ${lease.id}")
      _ <- scheduleLeaseRenewal(lease, holderID)
    } yield ()).handleErrorWith { throwable =>
      Logger[F].error(show"Fatal failure in renewing lease ${lease.id}: ${throwable.getMessage}, giving up...")
    }

  private def renewLease(lease: KubeLease[F]) =
    for {
      now  <- Clock[F].realTimeInstant
      data <- lease.data.get
      renewedData = data.copy(duration = parameters.leaseDuration, lastRenewTime = Some(now))
      status <- api.createOrUpdate(k8sLeaseFromData(lease.id, renewedData))
      _ <- Applicative[F].unlessA(status.isSuccess)(
        MonadError[F, Throwable].raiseError(new RuntimeException(status.sanitizedReason))
      )
    } yield ()

  private def acquireLease(id: LeaseID, holder: HolderID, annotations: List[Annotation]): F[KubeLease[F]] = {
    for {
      maybeExistingLease <- getLease(id)
      heldLease <- maybeExistingLease
        .map(claimUntilHeld(_, holder, annotations))
        .getOrElse(createLease(id, holder, annotations))
      _ <- Logger[F].info(show"Acquired lease $id for $holder")
    } yield heldLease
  }

  private def claimUntilHeld(
      existingLease: KubeLease[F],
      claimant: HolderID,
      annotations: List[Annotation]
  ): F[KubeLease[F]] =
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
          _     <- existingLease.delete
          lease <- createLease(existingLease.id, claimant, annotations)
        } yield lease
      )

  private def createLease(id: LeaseID, holderID: HolderID, annotations: List[Annotation]): F[KubeLease[F]] =
    (for {
      _                    <- OptionT.liftF(Logger[F].debug(show"Attempting to create lease $id for $holderID"))
      deferredCreatedLease <- OptionT.liftF(Deferred[F, Option[KubeLease[F]]])
      _   <- OptionT.liftF(nextAcquiredLeaseFor(id, holderID).flatTap(deferredCreatedLease.complete).start)
      now <- OptionT.liftF(Clock[F].realTimeInstant)
      status <- OptionT.liftF(
        api.createOrUpdate(k8sLease(id, holderID, labels, annotations, now, parameters.leaseDuration))
      )
      _ <- OptionT.liftF(
        if (!status.isSuccess) Logger[F].warn(show"Failed to create lease $id for $holderID: ${status.reason}")
        else ().pure[F]
      )
      _ <- OptionT.whenF(status.isSuccess)(
        Logger[F].debug(show"Successfully created lease $id for $holderID")
      )
      lease <- OptionT(deferredCreatedLease.get)
    } yield lease).getOrRaise(new RuntimeException(show"Failed to create lease $id for $holderID"))

  private def nextAcquiredLeaseFor(id: LeaseID, holderID: HolderID): F[Option[KubeLease[F]]] =
    leaseAcquiredTopic.subscribeUnbounded
      .filter(_.id === id)
      .evalFilter(_.holder.map(_ === holderID))
      .head
      .compile
      .last

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
    val labels                   = label :: others.toList
    val labelMap                 = labels.map(l => l.key.value -> l.value.value).toMap
    implicit val api: KubeApi[F] = new KubeApi[F](client, namespace)
    Random.scalaUtilRandom[F].toResource.flatMap { r =>
      implicit val random: Random[F] = r
      for {
        leaseList <- api.leasesApi.list(labelMap).toResource
        topics    <- Topics.resource
        leaseMap  <- AutoUpdatingLeaseMap(leaseList, topics)
        listRevision = leaseList.metadata.flatMap(_.resourceVersion)
        eventsStream = streamFrom(api.leasesApi.watch(labelMap, listRevision))
        _ <- Resource.make(eventsStream.broadcastThrough(topics.watcher.publish).compile.drain.start)(_.cancel)
      } yield new KubeLeaseRepository[F](labels, leaseMap, topics.events, topics.leaseAcquired)
    }
  }

  private def streamFrom[F[_]: Async: Logger](stream: fs2.Stream[F, Either[String, WatchEvent[v1.Lease]]]) = stream
    .evalTap(
      _.fold(
        error => Logger[F].error(show"Error watching api: $error"),
        event =>
          Logger[F].debug(
            s"Received event for ${event.`object`.metadata.flatMap(_.name).getOrElse("unknown")}: ${EventType
                .encodeEventType(event.`type`)
                .toString()}"
          )
      )
    )
    .map(_.toOption.flatMap(leaseEventFromV1))
    .collect { case Some(event) => event }

}
