package io.github.jchapuis.leases4s.impl

import cats.effect.{Concurrent, IO}
import cats.effect.kernel.{Async, Ref}
import cats.syntax.eq.*
import cats.syntax.functor.*
import cats.syntax.flatMap.*
import fs2.concurrent.{SignallingRef, Topic}
import io.github.jchapuis.leases4s.impl.model.{LeaseData, LeaseDataEvent, Version}
import io.github.jchapuis.leases4s.model.{HolderID, Label, LeaseID}
import io.github.jchapuis.leases4s.{HeldLease, Lease}

private[impl] class KubeLease[F[_]: Async](
    val id: LeaseID,
    val data: Ref[F, LeaseData],
    eventsTopic: Topic[F, LeaseDataEvent]
) extends Lease[F]
    with HeldLease[F] {
  private val sleepUntilDurationElapsed = fs2.Stream.eval(data.get.map(_.duration)).flatMap(fs2.Stream.sleep[F])
  private val deleted = eventsTopic.subscribeUnbounded.collect { case LeaseDataEvent.Deleted(id) if id === id => () }
  private val renewed = eventsTopic.subscribeUnbounded
    .collect { case LeaseDataEvent.Modified(id, LeaseData(_, _, _, _, _, Some(renewTime))) if id === id => renewTime }
    .flatMap(renewTime =>
      fs2.Stream.eval(data.get.map(_.lastRenewTime)).map {
        case Some(lastRenewTime) if renewTime.isAfter(lastRenewTime) => true
        case None                                                    => true
        case _                                                       => false
      }
    )
    .collect { case true => () }

  def isExpired: F[Boolean] =
    for {
      expiredSignal <- SignallingRef[F, Boolean](false)
      signallingDurationElapsed = sleepUntilDurationElapsed
        .interruptWhen(expiredSignal)
        .map(_ => expiredSignal.set(true))
      signallingDeleted = deleted.interruptWhen(expiredSignal).map(_ => expiredSignal.set(true))
      signallingRenewed = renewed.interruptWhen(expiredSignal).map(_ => expiredSignal.set(false))
      _ <- signallingDurationElapsed
        .concurrently(signallingDeleted)
        .concurrently(signallingRenewed)
        .compile
        .drain
      isExpired <- expiredSignal.get
    } yield isExpired

  def expired: fs2.Stream[F, Unit] = fs2.Stream.repeatEval(isExpired).filter(identity).map(_ => ())

  def holder: F[HolderID] = data.get.map(_.holder)

  def labels: F[List[Label]] = data.get.map(_.labels)

  def version: F[Version] = data.get.map(_.version)

  implicit def F: Concurrent[F] = Concurrent[F]
}
