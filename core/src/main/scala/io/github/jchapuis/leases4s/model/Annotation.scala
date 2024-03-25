package io.github.jchapuis.leases4s.model

import cats.Show
import cats.syntax.show.*

final case class Annotation private (key: KubeString, value: KubeString)
object Annotation {
  def apply(key: String, value: String): Option[Annotation] =
    for {
      key <- KubeString(key)
      value <- KubeString(value)
    } yield new Annotation(key, value)
  implicit val show: Show[Annotation] = Show.show(annotation => show"${annotation.key}=${annotation.value}")
}
