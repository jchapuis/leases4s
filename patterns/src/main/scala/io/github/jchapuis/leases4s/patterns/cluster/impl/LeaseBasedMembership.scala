package io.github.jchapuis.leases4s.patterns.cluster.impl

import cats.effect.kernel.{Async, Outcome}
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.github.jchapuis.leases4s.HeldLease
import io.github.jchapuis.leases4s.patterns.cluster.Membership.Card
import io.github.jchapuis.leases4s.patterns.cluster.impl.LeaseHelpers.*
import io.github.jchapuis.leases4s.patterns.cluster.{Member, Membership}
import Membership.*

private[impl] class LeaseBasedMembership[F[_]: Async](
    val member: Member,
    heldLease: HeldLease[F],
    cluster: LeaseBasedCluster[F]
) extends Membership[F] {

  def cards: F[Cards] =
    for {
      members  <- cluster.members.map(_.zipWithIndex.map { case (member, index) => (member, Card(index)) }.map(_.swap))
      holderID <- heldLease.holder
    } yield members.partition { case (_, member) => member.holderID === holderID } match {
      case (List((mine, _)), others) => Cards(Some(mine), others.toMap)
      case (Nil, others)             => Cards(None, others.toMap)
      case _                         => Cards(None, Map.empty)
    }

  def cardsChanges: fs2.Stream[F, Cards] =
    (fs2.Stream.eval(cards) ++ cluster.changes.evalMap(_ => cards)).changes

  def card: F[Option[Card]] = cards.map(_.mine)

  def cardChanges: fs2.Stream[F, Card] = cardsChanges.map(_.mine).unNone.changes

  def isExpired: F[Boolean] = heldLease.isExpired

  def expired: fs2.Stream[F, Unit] = heldLease.expired

  def guard[A](fa: F[A]): F[Outcome[F, Throwable, A]] = heldLease.guard(fa)

}
