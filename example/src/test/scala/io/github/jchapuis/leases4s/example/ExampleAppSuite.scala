package io.github.jchapuis.leases4s.example

import cats.effect.IO
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*

class ExampleAppSuite extends munit.CatsEffectSuite {
  private val httpClient = ResourceSuiteLocalFixture("client", EmberClientBuilder.default[IO].build)
  private val baseUri    = uri"https://s3-website-bucket.s3-website.localhost.localstack.cloud:4566/"

  test("index page is available") {
    for {
      client <- IO(httpClient())
      status <- client.get(baseUri)(response => IO(response.status))
    } yield assert(status.isSuccess)
  }

  test("uploading multiple files at once still leads to consistent index page") {}

  override def munitFixtures: Seq[Fixture[?]] = List(httpClient)
}
