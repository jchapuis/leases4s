package io.github.jchapuis.leases4s.impl

import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.Resource
import cats.effect.std.Random
import cats.effect.syntax.resource.*
import cats.effect.syntax.spawn.*
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import cats.syntax.show.*
import cats.syntax.either.*
import com.goyeau.kubernetes.client.{EventType, WatchEvent}
import io.github.jchapuis.leases4s.LeaseRepository.{LeaseEvent, LeaseParameters}
import io.github.jchapuis.leases4s.impl.AutoUpdatingLeaseMap.LeaseMap
import io.github.jchapuis.leases4s.impl.K8sHelpers.*
import io.github.jchapuis.leases4s.impl.RetryHelpers.*
import io.github.jchapuis.leases4s.impl.model.{LeaseData, LeaseDataEvent}
import io.github.jchapuis.leases4s.model.*
import io.github.jchapuis.leases4s.LeaseRepository
import io.k8s.api.coordination.v1
import org.typelevel.log4cats.Logger
import scala.jdk.DurationConverters.*

private[impl] class AutoUpdatingLeaseMap[F[_]: Temporal: Logger: Random](
    leaseMap: LeaseMap[F],
    topics: Topics[F],
    labels: List[Label]
)(implicit kubeApi: KubeApi[F], parameters: LeaseParameters) {
  private val labelMap = labels.map(l => l.key.value -> l.value.value).toMap
  private type ResourceF[A] = Resource[F, A]
  implicit val randomR: Random[ResourceF] = implicitly[Random[F]].mapK(Resource.liftK)

  def start: Resource[F, Unit] = listAndWatch.retryWithBackoff(
    throwable =>
      Logger[F]
        .warn(throwable)(
          "Auto-updating lease map flow failed, restarting it (events may have been lost in the meantime, but it's ok as list will be reset)"
        )
        .toResource,
    parameters.initialOnErrorRetryDelay
  )

  private def listAndWatch: Resource[F, Unit] = for {
    list <- kubeApi.leasesApi.list(labelMap).toResource
    listRevision = list.metadata.flatMap(_.resourceVersion)
    idDataTuples = list.items
      .map(lease => (lease.metadata.flatMap(_.name).flatMap(LeaseID(_)), leaseDataFromK8s(lease)))
      .collect { case (Some(id), Some(lease)) => id -> lease }
    leases <- idDataTuples.traverse { case (id, data) => createOrUpdateKubeLeaseFor(id, data) }.toResource
    _      <- topics.watcher.subscribeUnbounded.evalTap(handleDataEvent).compile.drain.background
    _      <- watcherStream(listRevision).evalMap(topics.watcher.publish1).compile.drain.background
  } yield ()

  private def watcherStream(startRevision: Option[String]): fs2.Stream[F, LeaseDataEvent] = {
    val watcher =
      kubeApi.leasesApi
        .watch(labelMap, startRevision)
        .timeoutOnPull(parameters.watcherStreamTimeout)
        .attempt
        .map(_.leftMap(_.getMessage))
        .map(_.flatten)
    watcher.zipWithPrevious
      .flatMap {
        case (previous, Left(throwable)) =>
          val maybeLastSeenRevision = previous match {
            case Some(Right(WatchEvent(_, lastEvent))) => lastEvent.metadata.flatMap(_.resourceVersion)
            case _                                     => None
          }
          fs2.Stream.eval(
            Logger[F].debug(
              show"Refreshing watcher subscription from revision ${maybeLastSeenRevision.getOrElse("initial")} (trigger: $throwable)"
            )
          ) >> watcherStream(maybeLastSeenRevision)
        case (_, Right(event)) =>
          fs2.Stream
            .eval(
              Logger[F].debug(
                s"Received event for ${event.`object`.metadata.flatMap(_.name).getOrElse("unknown")}: ${EventType
                    .encodeEventType(event.`type`)
                    .toString}"
              )
            )
            .map(_ => leaseEventFromV1(event))
            .collect { case Some(event) => event }
      }
  }

  private def createOrUpdateKubeLeaseFor(id: LeaseID, data: LeaseData): F[KubeLease[F]] =
    for {
      map <- leaseMap.get
      lease <- map.get(id) match {
        case Some(lease) => lease.data.set(data) >> lease.pure[F]
        case None =>
          for {
            ref   <- Ref.of(data)
            lease <- KubeLease[F](id, ref, topics.watcher)
            _     <- leaseMap.update(_.+(id -> lease))
          } yield lease
      }
    } yield lease

  private def handleDataEvent(leaseDataEvent: LeaseDataEvent) = (leaseDataEvent match {
    case LeaseDataEvent.Added(id, data)        => handleAddedEvent(id, data)
    case LeaseDataEvent.Modified(id, modified) => handleModifiedEvent(id, modified)
    case LeaseDataEvent.Deleted(id)            => handleDeletedEvent(id).void
  }).handleErrorWith(throwable =>
    Logger[F].error(show"Failed to handle event for lease ${leaseDataEvent.id}: ${throwable.getMessage}, skipping...")
  )

  private def handleAddedEvent(id: LeaseID, data: LeaseData) =
    Logger[F].debug(show"Lease $id was added: $data") >> createAndPublishLeaseAcquired(id, data)

  private def handleDeletedEvent(id: LeaseID) =
    Logger[F].debug(show"Lease $id was deleted") >> markLeaseAsDeleted(id) >>
      leaseMap.update(_ - id) >> topics.events.publish1(LeaseEvent.Released(id))

  private def markLeaseAsDeleted(id: LeaseID) =
    OptionT(getLeaseFor(id)).flatMap(lease => OptionT.liftF(lease.data.update(_.copy(deleted = true)))).value.void

  private def handleModifiedEvent(id: LeaseID, modified: LeaseData) = {
    getLeaseFor(id).flatMap {
      case Some(lease) => handleModifiedLease(lease, modified)
      case None =>
        Logger[F].debug(show"Lease $id was recreated: $modified") >> createAndPublishLeaseAcquired(id, modified)
    }
  }

  private def handleModifiedLease(lease: KubeLease[F], modified: LeaseData) = {
    for {
      _                <- Logger[F].debug(show"Lease ${lease.id} was modified: $modified")
      hasHolderChanged <- lease.holder.map(_ =!= modified.holder)
      isLateRenewal <- lease.data.get
        .map(data =>
          (
            data.lastRenewTime.getOrElse(data.acquireTime),
            modified.lastRenewTime.getOrElse(modified.acquireTime)
          )
        )
        .map { case (priorTime, newTime) => newTime.isAfter(priorTime.plus(modified.duration.toJava)) }
      _ <- lease.data.set(modified)
      _ <-
        if (hasHolderChanged || isLateRenewal)
          Logger[F]
            .debug(
              show"Lease ${lease.id} was ${if (hasHolderChanged) "acquired" else "reacquired"} by ${modified.holder}"
            ) >> publishLeaseAcquired(modified.holder, lease)
        else Logger[F].debug(show"Lease ${lease.id} was renewed by ${modified.holder}")
    } yield ()
  }

  private def getLeaseFor(leaseID: LeaseID) = leaseMap.get.map(_.get(leaseID))

  private def createAndPublishLeaseAcquired(id: LeaseID, data: LeaseData) =
    createOrUpdateKubeLeaseFor(id, data).flatMap(lease => publishLeaseAcquired(data.holder, lease).void)

  private def publishLeaseAcquired(holderID: HolderID, lease: KubeLease[F]) =
    Logger[F].debug(show"Publishing lease acquired for $holderID") >>
      topics.events.publish1(LeaseEvent.Acquired(lease, holderID)) >> topics.leaseAcquired.publish1(lease).void

}

private[impl] object AutoUpdatingLeaseMap {
  type LeaseMap[F[_]] = Ref[F, Map[LeaseID, KubeLease[F]]]

  def apply[F[_]: Temporal: Logger: KubeApi: Random](topics: Topics[F], labels: List[Label])(implicit
      parameters: LeaseParameters
  ): Resource[F, LeaseMap[F]] =
    for {
      leaseMap <- Ref.of(Map.empty[LeaseID, KubeLease[F]]).toResource
      _        <- new AutoUpdatingLeaseMap[F](leaseMap, topics, labels).start
    } yield leaseMap

}
