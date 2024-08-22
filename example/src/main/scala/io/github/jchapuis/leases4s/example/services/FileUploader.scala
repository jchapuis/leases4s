package io.github.jchapuis.leases4s.example.services

import cats.effect.IO
import cats.syntax.parallel.*
import fs2.text
import io.github.jchapuis.leases4s.LeaseRepository
import io.github.jchapuis.leases4s.model.literals.*
import io.github.jchapuis.leases4s.model.{HolderID, LeaseID}
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}

class FileUploader(bucket: String)(using
    val client: S3AsyncClient,
    leaseRepository: LeaseRepository[IO],
    logger: Logger[IO]
):
  def uploadTextFile(name: String, description: String, file: String): IO[Int] = for {
    wordCount <- IO(countWordsIn(file))
    _         <- logger.info(s"Uploading file $name with description $description and $wordCount words")
    _         <- uploadFileStream(file, name, "text/plain")
    _         <- logger.info(s"File uploaded, now updating index files")
    _         <- safeUpdateIndexFiles(IndexPage.File(name, description, wordCount))
    _         <- logger.info(s"Index files updated")
  } yield wordCount

  private def safeUpdateIndexFiles(newFile: IndexPage.File) =
    leaseRepository
      .acquire(LeaseID(ks"file-uploader"), HolderID.unique)
      .use(_.guard(updateIndexFiles(newFile)).map(_.embedError))

  private def updateIndexFiles(newFile: IndexPage.File) =
    for {
      _     <- logger.info("Retrieving index page")
      files <- getIndexByNamePage.map(IndexPage.parseFiles)
      _     <- logger.info(s"Retrieved index page with ${files.size} files")
      _     <- uploadNewIndexFiles(files, newFile)
    } yield ()

  private def uploadNewIndexFiles(files: List[IndexPage.File], newFile: IndexPage.File) = {
    val newFiles              = files.toSet + newFile
    val newIndexByName        = IndexPage.render(IndexPage.Sorting.Name, newFiles)
    val newIndexByDescription = IndexPage.render(IndexPage.Sorting.Description, newFiles)
    val newIndexByWordCount   = IndexPage.render(IndexPage.Sorting.WordCount, newFiles)
    (
      uploadFileStream(newIndexByName, IndexPage.indexByNamePage, "text/html"),
      uploadFileStream(newIndexByDescription, IndexPage.indexByDescriptionPage, "text/html"),
      uploadFileStream(newIndexByWordCount, IndexPage.indexByWordCountPage, "text/html")
    ).parTupled.void
  }

  private def uploadFileStream(text: String, name: String, contentType: String) = for {
    _ <- logger.info(s"Uploading file $name with ${text.length} chars")
    fileUpload <- IO.fromCompletableFuture(
      IO(client.putObject(putObjectRequest(bucket, name, contentType), asyncPutFor(text)))
    )
    _ <- IO.raiseWhen(!fileUpload.sdkHttpResponse().isSuccessful)(new Exception(s"Failed to upload file: $fileUpload"))
  } yield ()

  private lazy val getIndexByNamePage: IO[String] = IO
    .fromCompletableFuture(
      IO(client.getObject(getObjectRequest(bucket, IndexPage.indexByNamePage), AsyncResponseTransformer.toBytes))
    )
    .map(response => new String(response.asByteArray(), "UTF-8"))

  private def countWordsIn(text: String) =
    fs2.Stream.emit(text).through(fs2.text.lines).flatMap(wordsIn).map(_ => 1).compile.foldMonoid

  private def wordsIn(line: String) = fs2.Stream.emits(line.split("\\s+"))

  private def putObjectRequest(bucket: String, name: String, contentType: String) =
    PutObjectRequest.builder().contentType(contentType).bucket(bucket).key(name).build()

  private def getObjectRequest(bucket: String, name: String) =
    GetObjectRequest.builder().bucket(bucket).key(name).build()

  private def asyncPutFor(text: String) = AsyncRequestBody.fromBytes(text.getBytes("UTF-8"))
