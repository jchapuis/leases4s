package io.github.jchapuis.leases4s.model

import cats.Show

import scala.language.implicitConversions
import scala.util.matching.Regex

final case class KubeString(value: String) extends AnyVal

object KubeString {
  def apply(value: String): Option[KubeString] = value match {
    case regex(_*) => Some(new KubeString(value))
    case _         => None
  }

  val regex: Regex = "^([a-z0-9]+(-[a-z0-9]+)*)+[a-z]{2,}$".r

  implicit def toStr(kubeString: KubeString): String = kubeString.value
  implicit val show: Show[KubeString]                = Show.show(_.value)
  implicit val eq: cats.kernel.Eq[KubeString]        = cats.Eq.fromUniversalEquals[KubeString]
}
