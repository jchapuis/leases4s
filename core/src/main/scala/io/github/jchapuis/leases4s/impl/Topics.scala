package io.github.jchapuis.leases4s.impl

import cats.effect.Concurrent
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.Resource
import fs2.concurrent.Topic
import io.github.jchapuis.leases4s.LeaseRepository.LeaseEvent
import io.github.jchapuis.leases4s.impl.model.LeaseDataEvent

private[impl] final case class Topics[F[_]](
    watcher: Topic[F, LeaseDataEvent],
    events: Topic[F, LeaseEvent[F]],
    leaseAcquired: Topic[F, KubeLease[F]]
)

private[impl] object Topics {
  def resource[F[_]: Concurrent]: Resource[F, Topics[F]] =
    for {
      watcher           <- Topic[F, LeaseDataEvent].toResource
      events            <- Topic[F, LeaseEvent[F]].toResource
      kubeLeaseAcquired <- Topic[F, KubeLease[F]].toResource
    } yield new Topics(watcher, events, kubeLeaseAcquired)
}
