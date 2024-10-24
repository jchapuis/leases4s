package io.github.jchapuis.leases4s.impl

import cats.effect.kernel.{Ref, Temporal}
import cats.effect.std.Random
import cats.effect.syntax.spawn.*
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.order.*
import cats.syntax.show.*
import cats.{Applicative, MonadError}
import fs2.concurrent.Topic
import io.github.jchapuis.leases4s.impl.JavaTimeHelpers.*
import io.github.jchapuis.leases4s.impl.KubeLease.DeleteFailedException
import io.github.jchapuis.leases4s.impl.model.{LeaseData, LeaseDataEvent, Version}
import io.github.jchapuis.leases4s.model.{Annotation, HolderID, Label, LeaseID}
import io.github.jchapuis.leases4s.{HeldLease, Lease}
import io.k8s.apimachinery.pkg.apis.meta.v1.{DeleteOptions, Preconditions}
import org.http4s.Status
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

private[impl] class KubeLease[F[_]: Temporal](
    val id: LeaseID,
    val data: Ref[F, LeaseData],
    eventsTopic: Topic[F, LeaseDataEvent]
) extends Lease[F]
    with HeldLease[F] {
  private val sleepUntilDurationElapsed = fs2.Stream.eval(data.get.map(_.duration)).flatMap(fs2.Stream.sleep[F])
  private val deleted = eventsTopic.subscribeUnbounded.collect { case LeaseDataEvent.Deleted(id) if id === id => () }
  private val renewed = eventsTopic.subscribeUnbounded
    .collect {
      case LeaseDataEvent.Modified(id, LeaseData(_, _, _, _, _, _, Some(renewTime), _)) if id === id => renewTime
    }
    .flatMap(renewTime =>
      fs2.Stream.eval(data.get.map(_.lastRenewTime)).map {
        case Some(lastRenewTime) if renewTime >= lastRenewTime => true
        case None                                              => true
        case _                                                 => false
      }
    )
    .collect { case true => () }

  def isExpired: F[Boolean] = data.get
    .map(_.deleted)
    .ifM(
      true.pure,
      renewed
        .map(_ => false)
        .merge(deleted.map(_ => true))
        .merge(sleepUntilDurationElapsed.map(_ => true))
        .head
        .compile
        .lastOrError
    )

  def expired: fs2.Stream[F, Unit] = fs2.Stream.repeatEval(isExpired).filter(identity).map(_ => ()).head

  def holder: F[HolderID] = data.get.map(_.holder)

  def labels: F[List[Label]] = data.get.map(_.labels)

  def annotations: F[List[Annotation]] = data.get.map(_.annotations)

  def version: F[Version] = data.get.map(_.version)

  def delete(implicit logger: Logger[F], kubeApi: KubeApi[F]): F[Unit] = data.get
    .map(_.deleted)
    .ifM(
      Applicative[F].unit,
      for {
        version <- version
        status <- kubeApi.leasesApi
          .delete(id, Some(DeleteOptions(preconditions = Some(Preconditions(resourceVersion = Some(version))))))
        _ <- if (status.isSuccess) data.update(_.copy(deleted = true)) else Applicative[F].unit
        _ <-
          status match {
            case Status.Ok       => Logger[F].debug(show"Deleted lease $id")
            case Status.NotFound => Logger[F].debug(show"Lease $id was already deleted")
            case _ =>
              Logger[F].debug(show"Failed to delete lease $id: ${status.reason}") >> MonadError[F, Throwable]
                .raiseError(new DeleteFailedException(id, status.reason))
          }
      } yield ()
    )
}

private[impl] object KubeLease {
  private[impl] class DeleteFailedException(id: LeaseID, reason: String)
      extends RuntimeException(show"Failed to delete lease $id: $reason")

  def apply[F[_]: Temporal: Logger: Random: KubeApi](
      id: LeaseID,
      data: Ref[F, LeaseData],
      eventsTopic: Topic[F, LeaseDataEvent]
  ): F[KubeLease[F]] = {
    val lease = new KubeLease(id, data, eventsTopic)
    startRevoker(lease).as(lease)
  }

  /** This spawns a fiber that watches for lease expiry and will repeatedly attempt to delete the expired lease after
    * some delay until its deleted flag is set to true, which happens eventually when the lease is deleted and we
    * receive a notification from the watcher. This active revocation mechanism makes sure we don't leak leases in the
    * cluster if a process dies before it can delete its lease. All replicas are thus contributing to the revocation of
    * expired leases. We use a random delay to avoid multiple nodes attempting to delete the lease at the same time.
    * @param lease
    *   The lease to cleanup
    * @tparam F
    *   The effect type
    * @return
    *   F effected with a fire and forget fiber that will revoke the lease
    */
  private def startRevoker[F[_]: Temporal: Logger: Random: KubeApi](
      lease: KubeLease[F]
  ): F[Unit] = {
    lazy val revokeOnExpired: F[Unit] =
      (lease.expired >> fs2.Stream.eval(ensureDeleted)).compile.drain.start.void
    lazy val ensureDeleted =
      for {
        randomDelay <- Random[F].betweenDouble(5.0, 10.0).map(_.seconds)
        _           <- Temporal[F].sleep(randomDelay)
        _           <- lease.data.get.map(_.deleted).ifM(Applicative[F].unit, attemptDelete)
      } yield ()
    lazy val attemptDelete = for {
      _ <- Logger[F].debug(show"Lease ${lease.id} has expired but hasn't been deleted, attempting to delete it")
      _ <- lease.delete
        .recoverWith { case error: DeleteFailedException =>
          Logger[F].debug(error)(
            show"Failed to cleanup expired lease ${lease.id}, attempting again later..."
          ) >> revokeOnExpired
        }
        .handleErrorWith(error =>
          Logger[F].debug(error)(show"Giving up attempts to cleanup lease ${lease.id} due to error")
        )
    } yield ()
    revokeOnExpired
  }
}
