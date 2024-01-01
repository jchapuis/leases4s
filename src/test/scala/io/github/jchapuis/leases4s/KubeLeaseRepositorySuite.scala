package io.github.jchapuis.leases4s

import cats.effect.IO
import io.github.jchapuis.leases4s.model.*
import io.github.jchapuis.leases4s.LeaseRepository.LeaseParameters
import io.github.jchapuis.leases4s.impl.KubeLeaseRepository
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.*

class KubeLeaseRepositorySuite extends CatsEffectSuite with K3dClientProvider {
  implicit lazy val logger: Logger[IO] = Slf4jLogger.getLogger[IO]
  private val testLabel = Label("foo", "bar").get
  private val holderID = HolderID("test-holder").get
  implicit private val parameters: LeaseParameters = LeaseParameters(leaseDuration = 2.seconds)

  test("a lease can be acquired and is automatically renewed before expiration") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        repository.acquire(LeaseID("acquire-test-lease").get, holderID).use { lease =>
          for {
            _ <- IO.sleep(parameters.leaseDuration + 1.second)
            expired <- lease.isExpired
          } yield assert(!expired)
        }
      }
    }
  }

  test("a single lease holder can exist at a time") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](testLabel).use { repository =>
        val singleLeaseID = LeaseID("single-holder-test-lease").get
        val legitHolder = repository.acquire(singleLeaseID, holderID)
        legitHolder.use(_ =>
          for {
            competingHolder <- repository
              .acquire(singleLeaseID, HolderID("competing-holder").get)
              .use(_ => IO(fail("should not succeed in acquiring the lease")))
              .start
            _ <- IO.sleep(parameters.leaseDuration + 1.second)
            _ <- competingHolder.cancel
          } yield ()
        )
      }
    }
  }

}
