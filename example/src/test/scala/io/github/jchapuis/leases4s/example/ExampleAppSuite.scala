package io.github.jchapuis.leases4s.example

import cats.effect.IO
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*
import org.http4s.multipart.*
import fs2.io.file.Files
import cats.syntax.traverse.*
import java.nio.file.Paths
import org.http4s.Header.*
import org.http4s.{MediaType, Method, Request}
import org.http4s.headers.`Content-Type`
import org.http4s.*

class ExampleAppSuite extends munit.CatsEffectSuite {
  private val httpClient = ResourceSuiteLocalFixture("client", EmberClientBuilder.default[IO].build)
  private val baseS3Uri  = uri"https://s3-website-bucket.s3-website.localhost.localstack.cloud:4566/"
  private val baseAppUri = uri"http://localhost:8080"

  test("index page is available") {
    for {
      client <- IO(httpClient())
      status <- client.statusFromUri(baseS3Uri)
    } yield assert(status.isSuccess)
  }

  test("uploading all books in resources folder") {
    for {
      client     <- IO(httpClient())
      multiparts <- Multiparts.forSync[IO]
      resourcePath = Paths.get("./example/src/main/resources/books")
      files <- Files[IO].list(fs2.io.file.Path.fromNioPath(resourcePath)).compile.toList
      success <- files
        .traverse { file =>
          for {
            contents <- Files[IO].readUtf8(file).compile.string
            namePart        = Part.formData[IO]("name", file.fileName.toString)
            descriptionPart = Part.formData[IO]("description", "A book from resources folder")
            filePart        = Part.formData[IO]("file", contents, `Content-Type`(MediaType.text.plain))
            multipart <- multiparts.multipart(Vector(filePart, namePart, descriptionPart))
            _         <- IO(println(s"Uploading file: ${file.fileName} with contents of size ${contents.length}"))
            request = Request[IO](method = Method.POST, uri = baseAppUri / "uploads" / "file").withEntity(multipart)
            success <- client.successful(request)
          } yield success
        }
        .map(_.forall(identity))
    } yield {
      assert(success)
    }
  }

  override def munitFixtures: Seq[Fixture[?]] = List(httpClient)
}
