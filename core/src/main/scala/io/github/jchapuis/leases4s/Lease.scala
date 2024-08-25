package io.github.jchapuis.leases4s

import io.github.jchapuis.leases4s.model.*

trait Lease[F[_]] {

  /** Lease identifier
    * @return
    *   the lease identifier
    */
  def id: LeaseID

  /** Identifier of the lease holder
    * @return
    *   the current lease holder
    */
  def holder: F[HolderID]

  /** Labels associated with the lease. Labels are inherited from the repository's.
    * @return
    *   the current lease labels
    */
  def labels: F[List[Label]]

  /** Annotations associated with the lease
    * @return
    *   the current lease annotations
    */
  def annotations: F[List[Annotation]]

  /** Returns a boolean flag as soon as can figure out the state of the lease: we semantically block until whichever
    * comes first:
    *   - the duration of the lease is elapsed (we can't compare times because of clock skew)
    *   - the lease is deleted
    *   - it is renewed by the holder (in which case return false)
    *
    * @return
    *   true if the lease is expired, false otherwise
    */
  def isExpired: F[Boolean]

  /** Stream that emits a single unit value upon lease expiry
    */
  def expired: fs2.Stream[F, Unit]
}
