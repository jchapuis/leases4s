package io.github.jchapuis.leases4s.model

import cats.Show
import cats.kernel.Eq

final case class LeaseID private (value: KubeString)
object LeaseID {
  def apply(value: String): Option[LeaseID] = KubeString.fromString(value).map(LeaseID(_))
  implicit def toStr(leaseID: LeaseID): String = leaseID.value.value
  implicit val show: Show[LeaseID] = Show.show(_.value)
  implicit val eq: Eq[LeaseID] = cats.Eq.fromUniversalEquals[LeaseID]
}
