package io.github.jchapuis.leases4s.impl

import cats.effect.kernel.Temporal
import cats.effect.std.Random
import cats.syntax.applicativeError.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*

import scala.concurrent.duration.*

private[impl] object RetryHelpers {
  implicit class RetryOps[F[_], A](fa: F[A]) {
    def retryWithBackoff(
        onError: Throwable => F[Unit],
        initialDelay: Duration
    )(implicit
        temporal: Temporal[F],
        random: Random[F]
    ): F[A] = {
      fa.handleErrorWith { error =>
        onError(error) *> temporal.sleep(initialDelay) *> Random[F].betweenDouble(1, 2) >>= (factor =>
          fa.retryWithBackoff(onError, initialDelay * factor)
        )
      }
    }
  }

}
