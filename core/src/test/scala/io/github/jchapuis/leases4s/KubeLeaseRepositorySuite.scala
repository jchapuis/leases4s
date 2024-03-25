package io.github.jchapuis.leases4s

import cats.effect.kernel.{Clock, Outcome, Ref}
import cats.effect.{Deferred, IO}
import cats.syntax.flatMap.*
import cats.syntax.parallel.*
import cats.syntax.show.*
import io.github.jchapuis.leases4s.LeaseRepository.{LeaseEvent, LeaseParameters}
import io.github.jchapuis.leases4s.impl.K8sHelpers.*
import io.github.jchapuis.leases4s.impl.KubeLeaseRepository
import io.github.jchapuis.leases4s.model.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.*

class KubeLeaseRepositorySuite extends CatsEffectSuite with K3dClientProvider {
  override def munitIOTimeout: FiniteDuration = 60.seconds
  implicit lazy val logger: Logger[IO] = Slf4jLogger.getLogger[IO]
  private val testLabel = Label("foo", "bar").get
  private val testAnnotations =
    List(Annotation("test", "first-annotation").get, Annotation("test", "second-annotation").get)
  private val holderID = HolderID("test-holder").get
  implicit private val parameters: LeaseParameters = LeaseParameters(leaseDuration = 10.seconds)
  private lazy val sleep = IO.sleep(1.second)

  test("a lease can be acquired and is automatically renewed (before expiration)") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        repository.acquire(LeaseID("acquire-test-lease").get, holderID).use { lease =>
          for {
            _ <- IO.sleep(parameters.leaseDuration + 1.second)
            _ <- assertIOBoolean(lease.isExpired.map(!_))
            _ <- sleep // sleep a bit more and assert again it's still held
            _ <- assertIOBoolean(lease.isExpired.map(!_))
          } yield ()
        }
      }
    }
  }

  test("a single lease holder can exist at a time") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        val singleLeaseID = LeaseID("single-holder-test-lease").get
        for {
          (_, release) <- repository.acquire(singleLeaseID, holderID).allocated
          competing <- repository
            .acquire(singleLeaseID, HolderID("competing-holder").get)
            .use(_ => IO(fail("should not succeed in acquiring the lease")))
            .start
          _ <- IO.sleep(parameters.leaseDuration + 1.second)
          _ <- competing.cancel
          _ <- release
        } yield ()
      }
    }
  }

  test("competing holders grab the lease once the current holder releases it") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        val singleLeaseID = LeaseID("competition-test-lease").get
        for {
          (_, release) <- repository.acquire(singleLeaseID, holderID).allocated
          firstCompetitor <- repository
            .acquire(singleLeaseID, HolderID("first-competitor").get)
            .allocated
            .map { case (_, release) => release }
            .start
          secondCompetitor <- repository
            .acquire(singleLeaseID, HolderID("second-competitor").get)
            .allocated
            .map { case (_, release) => release }
            .start
          _ <- release
          _ <- sleep
          (releaseNext, lastCompetitor) <- IO
            .racePair(
              firstCompetitor.joinWithNever,
              secondCompetitor.joinWithNever
            )
            .map {
              case Left((winner, loser))  => (winner, loser)
              case Right((loser, winner)) => (winner, loser)
            }
            .map { case (winner, loser) =>
              (winner.fold(fail("should not cancel"), fail("should not throw", _), _.flatten), loser)
            }
          _ <- sleep
          _ <- releaseNext
          finalRelease <- lastCompetitor.joinWithNever
          _ <- finalRelease
        } yield ()
      }
    }
  }

  test("lease acquisition and release can be watched") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          acquired <- Deferred[IO, HolderID]
          released <- Deferred[IO, LeaseID]
          _ <- repository.watcher
            .collect { case LeaseEvent.Acquired(_, holderID) => holderID }
            .evalTap(acquired.complete)
            .compile
            .drain
            .start
          _ <- repository.watcher
            .collect { case LeaseEvent.Released(leaseID) => leaseID }
            .evalTap(released.complete)
            .compile
            .drain
            .start
          leaseID = LeaseID("watcher-test-lease").get
          _ <- repository.acquire(leaseID, holderID).use_
          _ <- acquired.get.assertEquals(holderID).&>(released.get.assertEquals(leaseID))
        } yield ()
      }
    }
  }

  test("handle in event allows introspecting lease properties") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          deferredHandle <- Deferred[IO, Lease[IO]]
          _ <- repository.watcher
            .collect { case LeaseEvent.Acquired(lease, _) => lease }
            .evalTap(deferredHandle.complete)
            .compile
            .drain
            .start
          (lease, release) <- repository
            .acquire(LeaseID("introspection-test-lease").get, holderID, testAnnotations)
            .allocated
          handle <- deferredHandle.get
          _ <- IO(assertEquals(handle.id, lease.id))
          _ <- handle.holder.both(lease.holder) >>= assertTupleEqual
          _ <- handle.labels.both(lease.labels) >>= assertTupleEqual
          _ <- handle.annotations.both(lease.annotations) >>= assertTupleEqual
          _ <- handle.isExpired.both(lease.isExpired) >>= assertTupleEqual
          _ <- release
        } yield ()
      }
    }
  }

  test("handle returned by get allows introspecting lease properties") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          (lease, release) <- repository.acquire(LeaseID("get-test-lease").get, holderID, testAnnotations).allocated
          handle <- repository.get(lease.id).map(_.get)
          _ <- IO(assertEquals(lease.id, handle.id))
          _ <- handle.holder.both(lease.holder) >>= assertTupleEqual
          _ <- handle.labels.both(lease.labels) >>= assertTupleEqual
          _ <- handle.annotations.both(lease.annotations) >>= assertTupleEqual
          _ <- handle.isExpired.both(lease.isExpired) >>= assertTupleEqual
          _ <- release
        } yield ()
      }
    }
  }

  test("handle indicates isExpired after release") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          (lease, release) <- repository.acquire(LeaseID("is-expired-after-release-test-lease").get, holderID).allocated
          _ <- sleep // allow some time to avoid DELETE version conflict
          _ <- release
          _ <- sleep
          _ <- assertIOBoolean(lease.isExpired)
        } yield ()
      }
    }
  }

  test("release removes lease from repository") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          (lease, release) <- repository.acquire(LeaseID("no-handle-after-release-test-lease").get, holderID).allocated
          _ <- sleep // allow some time to avoid DELETE version conflict
          _ <- release
          _ <- sleep
          _ <- assertIOBoolean(repository.get(lease.id).map(_.isEmpty))
        } yield ()
      }
    }
  }

  test("lease handle allows watching for expiry (explicit release is also considered an expiry by the handle)") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          expired <- Deferred[IO, Unit]
          (lease, release) <- repository.acquire(LeaseID("expiry-test-lease").get, holderID).allocated
          _ <- lease.expired.evalTap(expired.complete).compile.drain.start
          _ <- release
          _ <- expired.get
        } yield ()
      }
    }
  }

  test("reacquired stray lease is considered the same as acquired") {
    withKubeClient { implicit client =>
      val leaseID = LeaseID("stray-test-lease").get
      for {
        now <- Clock[IO].realTimeInstant
        kubeApi = client.leases.namespace(namespace)
        _ <- kubeApi.delete(leaseID)
        simulatedStrayLeaseStatus <- kubeApi.createOrUpdate(
          k8sLease(leaseID, holderID, List(testLabel), testAnnotations, now, parameters.leaseDuration)
        )
        _ <- logger.info(show"Simulated stray lease status: ${simulatedStrayLeaseStatus.sanitizedReason}")
        _ <- IO(simulatedStrayLeaseStatus.isSuccess).assert
        _ <- IO.sleep(parameters.leaseDuration + 1.second)
        _ <- KubeLeaseRepository[IO](testLabel).use { repository =>
          for {
            acquired <- Deferred[IO, HolderID]
            _ <- repository.watcher
              .collect { case LeaseEvent.Acquired(_, holderID) => holderID }
              .evalTap(acquired.complete)
              .compile
              .drain
              .start
            (_, release) <- repository.acquire(leaseID, holderID).allocated
            _ <- acquired.get.assertEquals(holderID)
            _ <- release
          } yield ()
        }
      } yield ()
    }
  }

  test("stray leases are cleaned up eventually") {
    withKubeClient { implicit client =>
      val leaseID = LeaseID("stray-cleanup-test-lease").get
      for {
        now <- Clock[IO].realTimeInstant
        kubeApi = client.leases.namespace(namespace)
        _ <- kubeApi.delete(leaseID)
        simulatedStrayLeaseStatus <- kubeApi.createOrUpdate(
          k8sLease(leaseID, holderID, List(testLabel), testAnnotations, now, parameters.leaseDuration)
        )
        _ <- IO(simulatedStrayLeaseStatus.isSuccess).assert
        _ <- KubeLeaseRepository[IO](testLabel).use { repository =>
          for {
            _ <- repository.get(leaseID).map(_.isDefined).assert
            _ <- IO.sleep(parameters.leaseDuration + 15.seconds)
            _ <- repository.get(leaseID).map(_.isEmpty).assert
          } yield ()
        }
      } yield ()
    }
  }

  test("long-running operation executes to completion atomically under guard") {
    withKubeClient { implicit client =>
      val leaseID = LeaseID("guard-test-lease").get
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          counter <- Ref.of[IO, Int](1)
          _ <- (1 to 10).toList
            .map(index =>
              repository
                .acquire(leaseID, HolderID(s"test-holder-${index}idx").get)
                .map(_.guard(counter.update(_ - 1) >> sleep >> assertIO(counter.get, 0) >> counter.update(_ + 1)))
                .use_
            )
            .parSequence
          _ <- assertIO(counter.get, 1)
        } yield ()
      }
    }
  }

  test("cancel guarded operation if the lease is released in the meantime") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          boolean <- Ref.of[IO, Boolean](false)
          (lease, release) <- repository.acquire(LeaseID("guard-cancel-test-lease").get, holderID).allocated
          fiber <- lease
            .guard(sleep >> boolean.set(true))
            .map {
              case Outcome.Succeeded(_) => fail("should not succeed")
              case Outcome.Canceled()   => IO.unit
              case Outcome.Errored(_)   => fail("should not error")
            }
            .start
          _ <- release
          _ <- assertIO(boolean.get, false)
          _ <- fiber.join
        } yield ()
      }
    }
  }

  test("raised error in guarded operation leads to error outcome") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        for {
          _ <- repository
            .acquire(LeaseID("guard-error-test-lease").get, holderID)
            .use(
              _.guard(IO.raiseError[Unit](new RuntimeException("test error")))
                .map {
                  case Outcome.Succeeded(_) => fail("should not succeed")
                  case Outcome.Canceled()   => fail("should not cancel")
                  case Outcome.Errored(_)   => IO.unit
                }
            )
        } yield ()
      }
    }
  }

  private def assertTupleEqual[A](tuple: (A, A)): IO[Unit] = tuple match {
    case (a, b) => IO(a).assertEquals(b)
  }
}
