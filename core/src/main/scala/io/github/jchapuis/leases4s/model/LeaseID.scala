package io.github.jchapuis.leases4s.model

import cats.Show
import cats.kernel.Eq
import scala.language.implicitConversions

/** Identifier for a lease, compatible with Kubernetes naming constraints
  * @param value
  *   the lease identifier
  */
final case class LeaseID(value: KubeString)
object LeaseID {
  def apply(value: String): Option[LeaseID]    = KubeString(value).map(new LeaseID(_))
  implicit def toStr(leaseID: LeaseID): String = leaseID.value.value
  implicit val show: Show[LeaseID]             = Show.show(_.value)
  implicit val eq: Eq[LeaseID]                 = cats.Eq.fromUniversalEquals[LeaseID]
}
