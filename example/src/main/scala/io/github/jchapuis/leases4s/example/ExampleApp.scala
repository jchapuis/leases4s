package io.github.jchapuis.leases4s.example

import cats.effect.*
import cats.instances.option.*
import cats.syntax.contravariantSemigroupal.*
import cats.syntax.eq.*
import com.comcast.ip4s.*
import com.goyeau.kubernetes.client.{KubeConfig, KubernetesClient}
import io.github.jchapuis.leases4s.LeaseRepository
import io.github.jchapuis.leases4s.example.services.FileUploader
import io.github.jchapuis.leases4s.model.literals.*
import io.github.jchapuis.leases4s.model.{Label, Namespace}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.multipart.*
import org.http4s.server.middleware
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import java.net.URI

object ExampleApp extends IOApp {
  val localStackEndpoint = "http://localhost.localstack.cloud:4566";
  given S3AsyncClient =
    S3AsyncClient
      .builder()
      .credentialsProvider(DefaultCredentialsProvider.create())
      .region(Region.US_EAST_1)
      .endpointOverride(URI.create(localStackEndpoint))
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build()

  given Namespace = Namespace.Default

  private def kubeConfig(using Logger[IO]): IO[KubeConfig[IO]] = KubeConfig.cluster[IO].handleErrorWith { throwable =>
    Logger[IO].error(throwable)("Failed to load cluster kubeconfig") *> IO.raiseError(throwable)
  }

  private def service(using fileUploader: FileUploader, logger: Logger[IO]) = HttpRoutes.of[IO] {
    case GET -> Root / "uploads" / "health" => Ok("OK")
    case req @ POST -> Root / "uploads" / "file" =>
      req.decode[Multipart[IO]] { form =>
        val parts = form.parts.toList
        (
          parts.find(_.name.contains("file")),
          parts.find(_.name.contains("name")),
          parts.find(_.name.contains("description"))
        )
          .mapN {
            case (file, name, description)
                if file.headers.get(ci"Content-Type").exists(_.exists(_.value === "text/plain")) =>
              for {
                fileName        <- name.body.through(fs2.text.utf8.decode).compile.string
                fileDescription <- description.body.through(fs2.text.utf8.decode).compile.string
                fileText        <- file.body.through(fs2.text.utf8.decode).compile.string
                wordCount       <- fileUploader.uploadTextFile(fileName, fileDescription, fileText)
              } yield SeeOther(Location(uri"https://s3-website-bucket.s3-website.localhost.localstack.cloud:4566/"))
            case _ => IO(BadRequest("Not a text file"))
          }
          .getOrElse(IO(BadRequest("Invalid form")))
          .flatten
          .handleErrorWith { throwable =>
            logger.error(throwable)("Failed to upload file") *> InternalServerError(
              "Failed to upload file: " + throwable.getMessage
            )
          }
      }
  }

  private val app = for {
    given Logger[IO]           <- Slf4jLogger.create[IO].toResource
    given KubernetesClient[IO] <- KubernetesClient[IO](kubeConfig)
    given LeaseRepository[IO]  <- LeaseRepository.kubernetes[IO](Label(ks"app", ks"example"))
    given FileUploader = FileUploader("s3-website-bucket")
    uploaderService    = service
    httpServer <- EmberServerBuilder
      .default[IO]
      .withLogger(Logger[IO])
      .withHost(host"0.0.0.0")
      .withPort(port"80")
      .withoutTLS
      .withHttpApp(
        middleware.Logger.httpApp[IO](
          logHeaders = true,
          logBody = false,
          redactHeadersWhen = _ => false,
          logAction = Some((msg: String) => Logger[IO].info(msg))
        )(service.orNotFound)
      )
      .build
  } yield ()

  def run(args: List[String]): IO[ExitCode] = app.useForever.as(ExitCode.Success)
}
