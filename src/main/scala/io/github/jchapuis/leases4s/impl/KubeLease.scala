package io.github.jchapuis.leases4s.impl

import cats.effect.Concurrent
import cats.effect.kernel.{Async, Ref}
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.order.*
import fs2.concurrent.Topic
import io.github.jchapuis.leases4s.impl.model.{LeaseData, LeaseDataEvent, Version}
import io.github.jchapuis.leases4s.model.{HolderID, Label, LeaseID}
import io.github.jchapuis.leases4s.{HeldLease, Lease}
import JavaTimeHelpers.*

private[impl] class KubeLease[F[_]: Async](
    val id: LeaseID,
    val data: Ref[F, LeaseData],
    eventsTopic: Topic[F, LeaseDataEvent]
) extends Lease[F]
    with HeldLease[F] {
  private val sleepUntilDurationElapsed = fs2.Stream.eval(data.get.map(_.duration)).flatMap(fs2.Stream.sleep[F])
  private val deleted = eventsTopic.subscribeUnbounded.collect { case LeaseDataEvent.Deleted(id) if id === id => () }
  private val renewed = eventsTopic.subscribeUnbounded
    .collect {
      case LeaseDataEvent.Modified(id, LeaseData(_, _, _, _, _, Some(renewTime), _)) if id === id => renewTime
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

  def version: F[Version] = data.get.map(_.version)

  implicit def F: Concurrent[F] = Concurrent[F]
}
