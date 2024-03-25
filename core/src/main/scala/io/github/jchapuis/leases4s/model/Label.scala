package io.github.jchapuis.leases4s.model

import cats.Show
import cats.syntax.show.*

final case class Label private (key: KubeString, value: KubeString)
object Label {
  def apply(key: String, value: String): Option[Label] =
    for {
      key <- KubeString(key)
      value <- KubeString(value)
    } yield new Label(key, value)
  implicit val show: Show[Label] = Show.show(label => show"${label.key}=${label.value}")
}
