package io.github.jchapuis.leases4s.impl.model

import io.github.jchapuis.leases4s.model.LeaseID

private[impl] sealed trait LeaseDataEvent {
  def id: LeaseID
}
private[impl] object LeaseDataEvent {
  final case class Added(id: LeaseID, data: LeaseData)       extends LeaseDataEvent
  final case class Modified(id: LeaseID, updated: LeaseData) extends LeaseDataEvent
  final case class Deleted(id: LeaseID)                      extends LeaseDataEvent
}
