package io.github.jchapuis.leases4s.impl

import cats.Order

import java.time.Instant

private[impl] object JavaTimeHelpers {
  implicit val instantOrder: Order[Instant] = Order.from[Instant](_.compareTo(_))
}
