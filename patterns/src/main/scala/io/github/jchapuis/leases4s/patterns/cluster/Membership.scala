package io.github.jchapuis.leases4s.patterns.cluster
import Membership.*
import cats.effect.kernel.Outcome
import cats.kernel.Eq

trait Membership[F[_]] {
  def member: Member
  def card: F[Option[Card]]
  def cardChanges: fs2.Stream[F, Card]
  def cards: F[Cards]
  def cardsChanges: fs2.Stream[F, Cards]
  def isExpired: F[Boolean]
  def expired: fs2.Stream[F, Unit]
  def guard[A](fa: F[A]): F[Outcome[F, Throwable, A]]
}

object Membership {
  final case class Card(index: Int) extends AnyVal
  object Card {
    implicit val eq: Eq[Card] = Eq.fromUniversalEquals[Card]
  }

  final case class Cards(mine: Option[Card], others: Map[Card, Member])
  object Cards {
    implicit val eq: Eq[Cards] = Eq.fromUniversalEquals[Cards]
  }
}
