package io.github.jchapuis.leases4s.example

import cats.effect.IO
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*
import org.http4s.multipart.*
import fs2.io.file.Files
import cats.syntax.parallel.*
import io.github.jchapuis.leases4s.example.services.IndexPage

import java.nio.file.Paths
import org.http4s.Header.*
import org.http4s.{MediaType, Method, Request}
import org.http4s.headers.`Content-Type`
import org.http4s.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.Duration

class ExampleAppSuite extends munit.CatsEffectSuite {
  override val munitTimeout = Duration(3, "m")
  private val httpClient    = ResourceSuiteLocalFixture("client", EmberClientBuilder.default[IO].build)
  private val baseS3Uri     = uri"https://s3-website-bucket.s3-website.localhost.localstack.cloud:4566/"
  private val baseAppUri    = uri"http://0.0.0.0:8080"

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
        .parTraverse { file =>
          for {
            contents <- Files[IO].readUtf8(file).compile.string
            namePart        = Part.formData[IO]("name", file.fileName.toString)
            descriptionPart = Part.formData[IO]("description", "Jules Verne's classic adventure novel")
            filePart        = Part.formData[IO]("file", contents, `Content-Type`(MediaType.text.plain))
            multipart <- multiparts.multipart(Vector(filePart, namePart, descriptionPart))
            _         <- IO(println(s"Uploading file: ${file.fileName} with contents of size ${contents.length}"))
            request = Request[IO](method = Method.POST, uri = baseAppUri / "uploads" / "file")
              .withEntity(multipart)
              .withHeaders(multipart.headers)
            success <- client.successful(request)
          } yield success
        }
        .map(_.forall(identity))
    } yield {
      assert(success)
    }
  }

  test("index.html and index-by-word-count.html have books sorted in the right order") {
    for {
      client               <- IO(httpClient())
      indexHtml            <- client.expect[String](baseS3Uri / IndexPage.indexByNamePage)
      indexByWordCountHtml <- client.expect[String](baseS3Uri / IndexPage.indexByWordCountPage)
      bookNames  = parseTableColumnFrom(indexHtml, 0)
      wordCounts = parseTableColumnFrom(indexByWordCountHtml, 2).map(_.toInt)
    } yield {
      assert(bookNames.nonEmpty)
      assertEquals(bookNames, bookNames.sorted)
      assert(wordCounts.nonEmpty)
      assertEquals(wordCounts, wordCounts.sorted)
    }
  }

  def parseTableColumnFrom(html: String, col: Int): List[String] = {
    val doc: Document   = Jsoup.parse(html)
    val table: Elements = doc.select("table")
    val rows: Elements  = table.select("tr")

    for {
      row <- rows.iterator().asScala.toList.tail // skip header row
      columns = row.select("td")
      if columns.size() > 0
    } yield {
      columns.get(col).text()
    }
  }

  override def munitFixtures: Seq[Fixture[?]] = List(httpClient)
}
