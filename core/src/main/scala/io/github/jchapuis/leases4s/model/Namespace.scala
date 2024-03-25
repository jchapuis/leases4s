package io.github.jchapuis.leases4s.model

import scala.language.implicitConversions

final case class Namespace private (value: KubeString)

object Namespace {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  val Default: Namespace = Namespace.apply("default").get

  def apply(value: String): Option[Namespace] = KubeString(value).map(new Namespace(_))
  implicit def toStr(namespace: Namespace): String = namespace.value
}
