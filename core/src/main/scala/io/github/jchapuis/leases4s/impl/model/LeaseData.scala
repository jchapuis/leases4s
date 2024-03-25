package io.github.jchapuis.leases4s.impl.model

import io.github.jchapuis.leases4s.model.{Annotation, HolderID, Label}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

private[impl] final case class LeaseData(
    holder: HolderID,
    labels: List[Label],
    annotations: List[Annotation],
    version: Version,
    duration: FiniteDuration,
    acquireTime: Instant,
    lastRenewTime: Option[Instant],
    deleted: Boolean = false
)

private[impl] object LeaseData {
  implicit val show: cats.Show[LeaseData] = cats.Show.fromToString
}
