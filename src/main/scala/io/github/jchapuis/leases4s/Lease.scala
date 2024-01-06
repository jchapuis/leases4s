package io.github.jchapuis.leases4s

import io.github.jchapuis.leases4s.model.*

import scala.language.implicitConversions

trait Lease[F[_]] {
  def id: LeaseID
  def holder: F[HolderID]
  def labels: F[List[Label]]

  /** Returns a boolean flag as soon as can figure out the state of the lease: we semantically block until whichever comes first:
    *  - the duration of the lease is elapsed (we can't compare times because of clock skew)
    *  - the lease is deleted
    *  - it is renewed by the holder (in which case return false)
    *
    * @return true if the lease is expired, false otherwise
    */
  def isExpired: F[Boolean]

  /** Singleton stream that emits a unit upon lease expiry
    */
  def expired: fs2.Stream[F, Unit]
}
