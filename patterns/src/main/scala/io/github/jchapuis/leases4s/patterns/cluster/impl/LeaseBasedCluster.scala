package io.github.jchapuis.leases4s.patterns.cluster.impl

import cats.effect.kernel.{Async, Resource}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import io.github.jchapuis.leases4s.LeaseRepository.LeaseParameters
import io.github.jchapuis.leases4s.model.{Annotation, KubeString, LeaseID}
import io.github.jchapuis.leases4s.patterns.cluster.impl.LeaseBasedCluster.*
import io.github.jchapuis.leases4s.patterns.cluster.impl.LeaseHelpers.*
import io.github.jchapuis.leases4s.patterns.cluster.{Cluster, Member, Membership}
import io.github.jchapuis.leases4s.{HeldLease, Lease, LeaseRepository}

private[impl] final class LeaseBasedCluster[F[_]: Async](val repository: LeaseRepository[F])(implicit
    leaseParameters: LeaseParameters,
    clusterParameters: Parameters
) extends Cluster[F] {
  def join(member: Member): Resource[F, Membership[F]] =
    acquireMembershipLease(member).map(lease => new LeaseBasedMembership(member, lease, this))

  private def acquireMembershipLease(member: Member): Resource[F, HeldLease[F]] =
    for {
      leaseID <- Resource.eval(membershipLeaseID)
      holderID <- Resource.pure(member.holderID)
      lease <- repository.acquire(leaseID, holderID, member.roles.map(Annotation("role", _)).toList.flatten)
    } yield lease

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def memberFor(lease: Lease[F]): F[Member] = for {
    roles <- lease.annotations
    holderID <- lease.holder
    splits = holderID.split(":")
  } yield Member(splits.head, splits.last.toInt, roles.map(_.value).toSet).get

  private lazy val membershipLeaseID: F[LeaseID] =
    repository.list.map(_.map(_.id.leaseIndex)).map(lowestAvailableIndexFrom).map(leaseIDFor)

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def lowestAvailableIndexFrom(indices: List[Int]): Int = {
    val sortedIndices = indices.sorted
    (0 to sortedIndices.size).find(!sortedIndices.contains(_)).get
  }

  def members: F[List[Member]] = for {
    leases <- repository.list
    sortedByIndex = leases.sortBy(_.id.leaseIndex)
    sortedMembers <- sortedByIndex.traverse(memberFor)
  } yield sortedMembers

  def changes: fs2.Stream[F, List[Member]] = repository.watcher.evalMap(_ => members).changes
}

object LeaseBasedCluster {
  final case class Parameters(clusterName: KubeString)

  def apply[F[_]: Async](
      repository: LeaseRepository[F]
  )(implicit parameters: Parameters, leaseParameters: LeaseParameters): Cluster[F] =
    new LeaseBasedCluster(repository)
}
