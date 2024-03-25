package io.github.jchapuis.leases4s.model

import cats.Show

import scala.language.implicitConversions

final case class KubeString private (value: String) extends AnyVal

object KubeString {
  def apply(value: String): Option[KubeString] = value match {
    case regex(_*) => Some(new KubeString(value))
    case _         => None
  }

  private val regex = "^([a-z0-9]+(-[a-z0-9]+)*)+[a-z]{2,}$".r

  implicit def toStr(kubeString: KubeString): String = kubeString.value
  implicit val show: Show[KubeString] = Show.show(_.value)
  implicit val eq: cats.kernel.Eq[KubeString] = cats.Eq.fromUniversalEquals[KubeString]
}
