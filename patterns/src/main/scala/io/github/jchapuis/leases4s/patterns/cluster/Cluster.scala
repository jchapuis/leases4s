package io.github.jchapuis.leases4s.patterns.cluster

import cats.effect.kernel.Resource

/** A cluster represents a dynamic group of indexed members that join and leave. A member's index can evolve over time.
  * Indices are indeed always lower than the number of members at any one time. Indices thus represent a slot in the
  * group and can be used to partition work or data.
  */
trait Cluster[F[_]] {

  /** Join the cluster with membership represented by resource scope. Resource release leads to leaving the cluster.
    * @note
    *   semantically blocks until the cluster membership is established.
    * @note
    *   while the resource is held, cluster membership is actively maintained, even in case of loss of connectivity with
    *   the Kubernetes API.
    * @return
    *   a stream of updates to the membership "card" as members join and leave
    */
  def join(member: Member): Resource[F, Membership[F]]

  def members: F[List[Member]]

  def changes: fs2.Stream[F, List[Member]]
}
