package io.github.jchapuis.leases4s

import cats.effect.Concurrent
import cats.effect.kernel.Outcome
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*

trait HeldLease[F[_]] extends Lease[F] {
  implicit def F: Concurrent[F]

  /** Runs the given action guarded by the lease
    *  - if the lease is still held after completion of the action, the outcome is `Outcome.Succeeded`
    *  - if the lease is already expired or expires in the meantime, the action is cancelled and the outcome is `Outcome.Canceled`
    *  - if the action fails, the outcome is `Outcome.Errored`
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
