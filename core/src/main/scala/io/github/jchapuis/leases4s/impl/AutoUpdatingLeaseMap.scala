package io.github.jchapuis.leases4s.impl

import cats.data.OptionT
import cats.effect.Temporal
import cats.effect.kernel.{Ref, Resource}
import cats.effect.std.Random
import cats.effect.syntax.resource.*
import cats.effect.syntax.spawn.*
import cats.syntax.applicativeError.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import cats.syntax.traverse.*
import fs2.concurrent.Topic
import io.github.jchapuis.leases4s.LeaseRepository.LeaseEvent
import io.github.jchapuis.leases4s.impl.AutoUpdatingLeaseMap.{LeaseMap, createKubeLeaseFor}
import io.github.jchapuis.leases4s.impl.K8sHelpers.leaseDataFromK8s
import io.github.jchapuis.leases4s.impl.model.{LeaseData, LeaseDataEvent}
import io.github.jchapuis.leases4s.model.*
import io.k8s.api.coordination.v1.LeaseList
import org.typelevel.log4cats.Logger

import scala.jdk.DurationConverters.*

private[impl] class AutoUpdatingLeaseMap[F[_]: Temporal: Logger: KubeApi: Random](
    leaseMap: LeaseMap[F],
    topics: Topics[F]
) {
  def start: Resource[F, Unit] =
    topics.watcher.subscribeUnbounded.evalTap(handleDataEvent).compile.drain.background.void

  private def handleDataEvent(leaseDataEvent: LeaseDataEvent) = (leaseDataEvent match {
    case LeaseDataEvent.Added(id, data)        => handleAddedEvent(id, data)
    case LeaseDataEvent.Modified(id, modified) => handleModifiedEvent(id, modified)
    case LeaseDataEvent.Deleted(id)            => handleDeletedEvent(id).void
  }).handleErrorWith(throwable =>
    Logger[F]
      .error(show"Failed to handle event for lease ${leaseDataEvent.id}: ${throwable.getMessage}, skipping...")
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
      _ <- Logger[F].debug(show"Lease ${lease.id} was modified: $modified")
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
    createKubeLeaseFor(id, data, topics.watcher).flatMap { case (id, lease) =>
      leaseMap.update(_.+(id -> lease)) >> publishLeaseAcquired(data.holder, lease).void
    }

  private def publishLeaseAcquired(holderID: HolderID, lease: KubeLease[F]) =
    Logger[F].debug(show"Publishing lease acquired for $holderID") >>
      topics.events.publish1(LeaseEvent.Acquired(lease, holderID)) >> topics.leaseAcquired.publish1(lease).void

}

private[impl] object AutoUpdatingLeaseMap {
  type LeaseMap[F[_]] = Ref[F, Map[LeaseID, KubeLease[F]]]

  def apply[F[_]: Temporal: Logger: KubeApi: Random](list: LeaseList, topics: Topics[F]): Resource[F, LeaseMap[F]] = {
    val idDataTuples = list.items
      .map(lease => (lease.metadata.flatMap(_.name).flatMap(LeaseID(_)), leaseDataFromK8s(lease)))
      .collect { case (Some(id), Some(lease)) => id -> lease }
    for {
      leases <- idDataTuples.traverse { case (id, data) => createKubeLeaseFor(id, data, topics.watcher) }.toResource
      leaseMap <- Ref.of(leases.toMap).toResource
      _ <- new AutoUpdatingLeaseMap[F](leaseMap, topics).start
    } yield leaseMap
  }

  private def createKubeLeaseFor[F[_]: Temporal: KubeApi: Logger: Random](
      id: LeaseID,
      data: LeaseData,
      watcherTopic: Topic[F, LeaseDataEvent]
  ) = Ref.of(data).flatMap(ref => KubeLease[F](id, ref, watcherTopic).map(lease => id -> lease))
}
