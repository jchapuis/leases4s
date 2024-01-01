package io.github.jchapuis.leases4s.impl

import cats.effect.kernel.{Async, Fiber, MonadCancel, Ref, Resource}
import cats.effect.std.Supervisor
import cats.effect.syntax.resource.*
import cats.effect.syntax.spawn.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import cats.syntax.show.*
import cats.syntax.applicativeError.*
import fs2.concurrent.Topic
import io.github.jchapuis.leases4s.LeaseRepository.LeaseEvent
import io.github.jchapuis.leases4s.impl.AutoUpdatingLeaseMap.{LeaseMap, createKubeLeaseFor}
import io.github.jchapuis.leases4s.impl.K8sHelpers.leaseDataFromK8s
import io.github.jchapuis.leases4s.impl.model.{LeaseData, LeaseDataEvent}
import io.github.jchapuis.leases4s.model.*
import io.k8s.api.coordination.v1.LeaseList
import org.typelevel.log4cats.Logger

private[impl] class AutoUpdatingLeaseMap[F[_]: Async: Logger](leaseMap: LeaseMap[F], topics: Topics[F]) {
  def start: Resource[F, Fiber[F, Throwable, Unit]] =
    Resource.make(topics.watcher.subscribeUnbounded.evalTap(handleDataEvent).compile.drain.start)(_.cancel)

  private def handleDataEvent(leaseDataEvent: LeaseDataEvent) =
    (leaseDataEvent match {
      case LeaseDataEvent.Added(id, data) =>
        Logger[F].debug(show"Lease $id was added: $data") >> createAndPublishLeaseAcquired(id, data)
      case LeaseDataEvent.Modified(id, data) =>
        leaseMap.get.map(_.get(id)).flatMap {
          case Some(lease) =>
            for {
              _ <- Logger[F].debug(show"Lease $id was modified: $data")
              hasHolderChanged <- lease.holder.map(_ =!= data.holder)
              _ <- lease.data.set(data)
              _ <-
                if (hasHolderChanged)
                  Logger[F]
                    .debug(show"Lease $id was acquired by ${data.holder}") >> publishLeaseAcquired(data.holder, lease)
                else Logger[F].debug(show"Lease $id was renewed by ${data.holder}")
            } yield ()
          case None =>
            Logger[F].debug(show"Lease $id was recreated: $data") >> createAndPublishLeaseAcquired(id, data)
        }
      case LeaseDataEvent.Deleted(id) =>
        Logger[F].debug(show"Lease $id was deleted") >> leaseMap.update(_ - id) >> topics.events.publish1(
          LeaseEvent.Released(id)
        )
    }).void.handleErrorWith(throwable =>
      Logger[F].error(show"Failed to handle event for lease ${leaseDataEvent.id}: ${throwable.getMessage}, skipping...")
    )

  private def createAndPublishLeaseAcquired(id: LeaseID, data: LeaseData) =
    createKubeLeaseFor(id, data, topics.watcher).flatMap { case (id, lease) =>
      leaseMap.update(_.+(id -> lease)) >> publishLeaseAcquired(data.holder, lease).void
    }

  private def publishLeaseAcquired(holderID: HolderID, lease: KubeLease[F]) =
    topics.events.publish1(LeaseEvent.Acquired(lease, holderID)) >> topics.leaseAcquired.publish1(lease).void

}

private[impl] object AutoUpdatingLeaseMap {
  type LeaseMap[F[_]] = Ref[F, Map[LeaseID, KubeLease[F]]]

  def apply[F[_]: Async: Logger](list: LeaseList, topics: Topics[F]): Resource[F, LeaseMap[F]] = {
    val idDataTuples = list.items
      .map(lease => (lease.metadata.flatMap(_.name).flatMap(LeaseID(_)), leaseDataFromK8s(lease)))
      .collect { case (Some(id), Some(lease)) => id -> lease }
    for {
      leases <- idDataTuples.traverse { case (id, data) => createKubeLeaseFor(id, data, topics.watcher) }.toResource
      leaseMap <- Ref.of(leases.toMap).toResource
      _ <- new AutoUpdatingLeaseMap[F](leaseMap, topics).start
    } yield leaseMap
  }

  private def createKubeLeaseFor[F[_]: Async](id: LeaseID, data: LeaseData, watcherTopic: Topic[F, LeaseDataEvent]) =
    Ref.of(data).map(ref => id -> new KubeLease[F](id, ref, watcherTopic))
}
