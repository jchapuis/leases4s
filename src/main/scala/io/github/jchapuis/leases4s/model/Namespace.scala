package io.github.jchapuis.leases4s.model

final case class Namespace private (value: KubeString)

object Namespace {
  val Default: Namespace = Namespace.apply("default").get

  def apply(value: String): Option[Namespace] = KubeString.fromString(value).map(Namespace(_))
  implicit def toStr(namespace: Namespace): String = namespace.value
}
