package io.github.jchapuis.leases4s.patterns.impl

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.option.*
import io.github.jchapuis.leases4s.K3dClientProvider
import io.github.jchapuis.leases4s.LeaseRepository.LeaseParameters
import io.github.jchapuis.leases4s.impl.KubeLeaseRepository
import io.github.jchapuis.leases4s.model.{KubeString, Label}
import io.github.jchapuis.leases4s.patterns.cluster.Member
import io.github.jchapuis.leases4s.patterns.cluster.Membership.{Card, Cards}
import io.github.jchapuis.leases4s.patterns.cluster.impl.LeaseBasedCluster
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationInt

class LeaseBasedClusterSuite extends CatsEffectSuite with K3dClientProvider {
  implicit lazy val logger: Logger[IO] = Slf4jLogger.getLogger[IO]
  implicit private val leaseParameters: LeaseParameters = LeaseParameters.Default
  implicit private val clusterParameters: LeaseBasedCluster.Parameters =
    LeaseBasedCluster.Parameters(KubeString.apply("test-cluster").get)
  private val repositoryLabel = Label("type", "cluster").get
  private val memberA = Member("localhost", 8080, Set(KubeString("master").get)).get
  private val memberB = Member("localhost", 8081, Set(KubeString("slave").get)).get
  private val memberC = Member("localhost", 8082, Set(KubeString("slave").get)).get

  private def assertStreamEmits[A](stream: fs2.Stream[IO, A])(value: A, others: A*): IO[Unit] =
    stream
      .zip(fs2.Stream.emits(NonEmptyList(value, others.toList).toList))
      .evalMap { case (a, b) => assertIO(IO.pure(a), b) }
      .compile
      .drain

  test("cards of members reflect cluster status") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](repositoryLabel).map(LeaseBasedCluster[IO]).use { cluster =>
        for {
          // first member joins
          (membershipA, releaseA) <- cluster.join(memberA).allocated

          // assertions for first member
          _ <- assertIO(membershipA.card, Card(0).some)
          _ <- assertIO(membershipA.cards, Cards(Some(Card(0)), Map.empty))
          memberACardStream <- assertStreamEmits(membershipA.cardChanges.take(1))(Card(0)).start
          memberACardsStream <- assertStreamEmits(membershipA.cardsChanges.take(3))(
            Cards(Some(Card(0)), Map.empty),
            Cards(Some(Card(0)), Map(Card(1) -> memberB)),
            Cards(Some(Card(0)), Map(Card(1) -> memberB, Card(2) -> memberC))
          ).start

          // second member joins
          (membershipB, releaseB) <- cluster.join(memberB).allocated

          // assertions for second member
          _ <- assertIO(membershipB.card, Card(1).some)
          _ <- assertIO(membershipB.cards, Cards(Some(Card(1)), Map(Card(0) -> memberA)))
          memberBCardStream <- assertStreamEmits(membershipB.cardChanges.take(2))(Card(1), Card(0)).start
          memberBCardsStream <- assertStreamEmits(membershipB.cardsChanges.take(3))(
            Cards(Some(Card(1)), Map(Card(0) -> memberA)),
            Cards(Some(Card(1)), Map(Card(0) -> memberA, Card(2) -> memberC)),
            Cards(Some(Card(0)), Map(Card(1) -> memberC))
          ).start

          // third member joins
          (membershipC, releaseC) <- cluster.join(memberC).allocated

          // assertions for third member
          _ <- assertIO(membershipC.card, Card(2).some)
          _ <- assertIO(membershipC.cards, Cards(Some(Card(2)), Map(Card(0) -> memberA, Card(1) -> memberB)))
          memberCCardStream <- assertStreamEmits(membershipC.cardChanges.take(3))(Card(2), Card(1), Card(0)).start
          memberCCardsStream <- assertStreamEmits(membershipC.cardsChanges.take(3))(
            Cards(Some(Card(2)), Map(Card(0) -> memberA, Card(1) -> memberB)),
            Cards(Some(Card(1)), Map(Card(0) -> memberB)),
            Cards(Some(Card(0)), Map.empty)
          ).start

          // first member leaves
          _ <- releaseA
          _ <- assertIOBoolean(
            membershipA.isExpired
          ) // this change is immediate, as we track deletion in the lease state
          _ <- IO.sleep(1.seconds) // wait for the change to propagate to the repository
          _ <- assertIOBoolean(membershipA.card.map(_.isEmpty))

          // second member leaves
          _ <- releaseB
          _ <- assertIOBoolean(membershipB.isExpired)
          _ <- IO.sleep(1.seconds)
          _ <- assertIOBoolean(membershipB.card.map(_.isEmpty))

          // third member leaves
          _ <- releaseC
          _ <- assertIOBoolean(membershipC.isExpired)
          _ <- IO.sleep(1.seconds)
          _ <- assertIOBoolean(membershipC.card.map(_.isEmpty))

          _ <- memberACardStream.joinWithUnit
          _ <- memberACardsStream.joinWithUnit
          _ <- memberBCardStream.joinWithUnit
          _ <- memberBCardsStream.joinWithUnit
          _ <- memberCCardStream.joinWithUnit
          _ <- memberCCardsStream.joinWithUnit
        } yield ()
      }
    }
  }

  test("cluster can be joined by multiple members and member changes reflect it") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](repositoryLabel).map(LeaseBasedCluster[IO]).use { cluster =>
        for {
          countStream <- assertStreamEmits(cluster.changes.take(5))(
            List(memberA),
            List(memberA, memberB),
            List(memberA, memberB, memberC),
            List(memberB, memberC),
            List(memberC),
            List.empty
          ).start
          (_, releaseA) <- cluster.join(memberA).allocated
          (_, releaseB) <- cluster.join(memberB).allocated
          (_, releaseC) <- cluster.join(memberC).allocated
          _ <- releaseA
          _ <- releaseB
          _ <- releaseC
          _ <- countStream.joinWithUnit
        } yield ()
      }
    }
  }

  test("long-running operation executes to completion atomically under membership guard") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](repositoryLabel)
        .map(LeaseBasedCluster[IO])
        .flatMap { cluster =>
          for {
            counter <- Ref.of[IO, Int](0).toResource
            membershipA <- cluster.join(memberA)
            membershipB <- cluster.join(memberB)
            membershipC <- cluster.join(memberC)
            guardedA <- membershipA.guard {
              for {
                _ <- IO.sleep(1.seconds)
                _ <- counter.update(_ + 1)
                _ <- IO.sleep(1.seconds)
                _ <- assertIO(counter.get, 1)
              } yield ()
            }.background
            _ <- guardedA.flatMap(_.embedError).flatMap(_.embedError).toResource
            guardedB <- membershipB.guard {
              for {
                _ <- counter.update(_ + 1)
                _ <- IO.sleep(1.seconds)
                _ <- assertIO(counter.get, 2)
              } yield ()
            }.background
            _ <- guardedB.flatMap(_.embedError).flatMap(_.embedError).toResource
            guardedC <- membershipC.guard {
              for {
                _ <- counter.update(_ + 1)
                _ <- assertIO(counter.get, 3)
              } yield ()
            }.background
            _ <- guardedC.flatMap(_.embedError).flatMap(_.embedError).toResource
          } yield ()
        }
        .use_
    }
  }

  test("cancel guarded operation if membership is rescinded in the meantime") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](repositoryLabel)
        .map(LeaseBasedCluster[IO])
        .use { cluster =>
          for {
            (membership, release) <- cluster.join(memberA).allocated
            operation <- membership.guard {
              for {
                _ <- IO.sleep(1.seconds)
                _ <- IO.raiseError(new RuntimeException("operation should be cancelled"))
              } yield ()
            }.start
            _ <- release
            outcome <- operation.join.flatMap(_.embedError)
          } yield assert(outcome.isCanceled)
        }
    }
  }

  test("raised error in guarded operation leads to error outcome") {
    withKubeClient { implicit client =>
      KubeLeaseRepository[IO](repositoryLabel)
        .map(LeaseBasedCluster[IO])
        .flatMap { cluster =>
          for {
            membership <- cluster.join(memberA)
            outcome <- membership.guard(IO.raiseError[Unit](new RuntimeException("operation should fail"))).toResource
          } yield assert(outcome.isError)
        }
        .use_
    }
  }
}
