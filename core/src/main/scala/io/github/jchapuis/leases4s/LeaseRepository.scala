package io.github.jchapuis.leases4s

import cats.effect.kernel.Resource
import io.github.jchapuis.leases4s.model.*
import io.github.jchapuis.leases4s.LeaseRepository.LeaseParameters.*
import io.github.jchapuis.leases4s.LeaseRepository.{LeaseEvent, LeaseParameters}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Defines a distributed lease repository, with the ability to acquire leases with automatic renewal, check for other existing leases and watch for related events.
  */
trait LeaseRepository[F[_]] {

  /** Labels of leases tracked by this repository (labels act as a filter)
    * @return
    */
  def labels: List[Label]

  /** Returns a resource representing acquisition of the lease and release upon finalization.
    * The resource acquire action is semantically blocking until the lease is acquired.
    * Once acquired, the lease is automatically renewed by a fiber supervised by the resource, according to passed parameters.
    * @param id lease ID
    * @param holderID holder ID
    * @param annotations optional annotations for this lease
    * @return resource representing the held lease
    */
  def acquire(id: LeaseID, holderID: HolderID, annotations: List[Annotation] = Nil)(implicit
      parameters: LeaseParameters = LeaseParameters.Default
  ): Resource[F, HeldLease[F]]

  /** Returns a lease with the corresponding ID, if any
    * @param id lease ID
    * @return handle on the lease, allowing to check for expiry
    */
  def get(id: LeaseID): F[Option[Lease[F]]]

  /** Lists all leases
    * @return list of handles on leases (allowing e.g. to check for expiry)
    */
  def list: F[List[Lease[F]]]

  /** Returns a stream indicating changes in the repository
    * @return stream of lease events
    */
  def watcher: fs2.Stream[F, LeaseEvent[F]]
}

object LeaseRepository {

  /** Parameters for lease acquisition and renewal
    * @param leaseDuration duration of the lease, after which the lease can be considered expired (unless it is renewed, which happens automatically while the lease is actively held)
    * @param renewalFrequencyRatio ratio of the lease duration at which the lease is renewed: this should be smaller than 1 to ensure safe operation (typically 1/3)
    * @param initialOnErrorRetryDelay base delay between retries in case of error, grows exponentially with each retry
    */
  final case class LeaseParameters(
      leaseDuration: FiniteDuration = DefaultLeaseDuration,
      renewalFrequencyRatio: Double = DefaultRenewalFrequencyRatio,
      initialOnErrorRetryDelay: FiniteDuration = DefaultOnErrorRetryDelay
  )
  object LeaseParameters {
    val DefaultRenewalFrequencyRatio: Double = 1d / 3
    val DefaultLeaseDuration: FiniteDuration = 10.seconds
    val DefaultOnErrorRetryDelay: FiniteDuration = 3.second
    val Default: LeaseParameters = LeaseParameters(
      DefaultLeaseDuration,
      DefaultRenewalFrequencyRatio,
      DefaultOnErrorRetryDelay
    )
  }

  /** Event indicating a change in the repository
    */
  sealed trait LeaseEvent[F[_]]
  object LeaseEvent {

    /** Lease was acquired by the specified holder
      * @param lease a handle on the lease that was acquired
      * @param holderID ID of the holder that acquired the lease
      */
    final case class Acquired[F[_]](lease: Lease[F], holderID: HolderID) extends LeaseEvent[F]

    /** Lease was released
      * @param leaseID ID of the lease that was released (deleted)
      */
    final case class Released[F[_]](leaseID: LeaseID) extends LeaseEvent[F]
  }
}
