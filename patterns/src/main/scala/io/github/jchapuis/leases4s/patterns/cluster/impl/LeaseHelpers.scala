package io.github.jchapuis.leases4s.patterns.cluster.impl

import cats.syntax.show.*
import io.github.jchapuis.leases4s.model.{HolderID, LeaseID}
import io.github.jchapuis.leases4s.patterns.cluster.Member

private[impl] object LeaseHelpers {
  implicit class LeaseIDOps[F[_]](leaseID: LeaseID) {
    def leaseIndex: Int = leaseID.value.value.split("-").reverse(1).toInt
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  implicit class MemberOps(member: Member) {
    def holderID: HolderID = HolderID(show"${member.host}:${member.port}").get
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def leaseIDFor(index: Int)(implicit clusterParameters: LeaseBasedCluster.Parameters): LeaseID = LeaseID(
    show"${clusterParameters.clusterName}-membership-$index-lease"
  ).get
}
