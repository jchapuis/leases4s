package io.github.jchapuis.leases4s

import cats.effect.Concurrent
import cats.effect.kernel.Outcome
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*

trait HeldLease[F[_]] extends Lease[F] {
  implicit def F: Concurrent[F]

  /** Runs the given action guarded by the lease: this implements a distributed critical section.
    *  - if the lease is still held after completion of the action, the outcome is `Outcome.Succeeded`
    *  - if the lease is already expired or expires in the meantime, the action is cancelled and the outcome is `Outcome.Canceled`
    *  - if the action fails, the outcome is `Outcome.Errored`
    *
    * @note The lease is auto-renewed in the background, so in nominal conditions there is no reason for it to expire during the action. However, in case of loss of connectivity to the lease issuer or some other issue preventing renewal of the lease, it may expire before the action completes. In such cases, the action is cancelled and the outcome is `Outcome.Canceled`.
    * @note This does not per-se ensure transactional semantics, as cancellation is best effort. If we fail to renew the lease and it gets revoked while running the action, cancellation or rollback must run before the lease expires. Otherwise, it is possible that another node acquires the lease upon expiry and enters the critical section while we are still trying to exit. If strict transactional semantics are required, the action should use some additional form of strong consistency like version control or idempotent operations.
    * @param fa action to run while holding the lease
    * @tparam A action result type
    * @return outcome of the action
    */
  def guard[A](fa: F[A]): F[Outcome[F, Throwable, A]] =
    fs2.Stream
      .eval(fa)
      .map(Option(_))
      .mergeHaltBoth(expired.map(_ => None))
      .map {
        case Some(a) => Outcome.succeeded[F, Throwable, A](a.pure)
        case None    => Outcome.canceled[F, Throwable, A]
      }
      .compile
      .lastOrError
      .handleError(Outcome.errored[F, Throwable, A])
}
