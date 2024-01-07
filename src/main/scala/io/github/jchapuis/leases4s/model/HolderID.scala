package io.github.jchapuis.leases4s.model

import cats.Show
import cats.kernel.Eq

final case class HolderID private (value: KubeString)
object HolderID {
  def apply(value: String): Option[HolderID] = KubeString.fromString(value).map(HolderID(_))
  implicit val eq: Eq[HolderID] = cats.Eq.fromUniversalEquals[HolderID]
  implicit def toStr(holderID: HolderID): String = holderID.value
  implicit val show: Show[HolderID] = Show.show(_.value)
}
