package io.github.jchapuis.leases4s.model

import cats.Show
import cats.kernel.Eq
import scala.language.implicitConversions

final case class HolderID(value: String)
object HolderID {
  def apply(value: String): Option[HolderID] = Option.when(value.nonEmpty)(new HolderID(value))
  def apply(value: KubeString): HolderID     = new HolderID(value.value)

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def unique: HolderID = new HolderID(java.util.UUID.randomUUID.toString)

  implicit val eq: Eq[HolderID]                  = cats.Eq.fromUniversalEquals[HolderID]
  implicit def toStr(holderID: HolderID): String = holderID.value
  implicit val show: Show[HolderID]              = Show.show(_.value)
}
