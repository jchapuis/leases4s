package io.github.jchapuis.leases4s.patterns.cluster

import cats.kernel.Eq
import io.github.jchapuis.leases4s.model.KubeString

final case class Member private (host: String, port: Int, roles: Set[KubeString])

object Member {
  def apply(host: String, port: Int, roles: Set[KubeString] = Set.empty): Option[Member] =
    Option.when(host.nonEmpty && port > 0)(new Member(host, port, roles))

  implicit val eq: Eq[Member] = cats.Eq.fromUniversalEquals[Member]
}
