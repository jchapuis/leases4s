package io.github.jchapuis.leases4s.example.services

import scalatags.Text.all.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

import scala.jdk.CollectionConverters.*
import scala.xml.{PrettyPrinter, XML}

object IndexPage {
  val indexByNamePage        = "index.html"
  val indexByDescriptionPage = "index-by-description.html"
  val indexByWordCountPage   = "index-by-word-count.html"
  val nameLabel              = "Name"
  val descriptionLabel       = "Description"
  val wordCountLabel         = "Word Count"

  def parseFiles(html: String): List[File] = {
    val doc: Document  = Jsoup.parse(html)
    val rows: Elements = doc.select("table tbody tr")
    rows.asScala
      .map { row =>
        val columns = row.select("td").asScala.toList
        for {
          name        <- columns.headOption.map(_.text())
          description <- columns.drop(1).headOption.map(_.text())
          wordCount   <- columns.drop(2).headOption.map(_.text().toInt)
        } yield IndexPage.File(name, description, wordCount)
      }
      .collect { case Some(file) => file }
      .toList
  }

  def render(sortBy: Sorting, files: Set[File]): String = {
    val raw = html(
      head(
        meta(charset := "UTF-8"),
        meta(name    := "viewport", content := "width=device-width, initial-scale=1.0"),
        scalatags.Text.tags2.title("Uploaded Files"),
        scalatags.Text.tags2.style()(PageStyles.styleSheetText)
      ),
      body(PageStyles.body)(
        div(PageStyles.container)(
          h1(PageStyles.heading)("Uploaded Files"),
          div(PageStyles.uploadLink, PageStyles.container)(
            a(PageStyles.container, PageStyles.a, PageStyles.aHover, PageStyles.uploadLinkA)(
              href := "upload.html",
              "Upload a file"
            )
          ),
          table(PageStyles.table)(
            thead(
              tr(
                th(PageStyles.th) {
                  sortBy match {
                    case Sorting.Name        => nameLabel
                    case Sorting.Description => a(href := indexByNamePage, nameLabel)
                    case Sorting.WordCount   => a(href := indexByNamePage, nameLabel)
                  }
                },
                th(PageStyles.th) {
                  sortBy match {
                    case Sorting.Name        => a(href := indexByDescriptionPage, descriptionLabel)
                    case Sorting.Description => descriptionLabel
                    case Sorting.WordCount   => a(href := indexByDescriptionPage, descriptionLabel)
                  }
                },
                th(PageStyles.th) {
                  sortBy match {
                    case Sorting.Name        => a(href := indexByWordCountPage, wordCountLabel)
                    case Sorting.Description => a(href := indexByWordCountPage, wordCountLabel)
                    case Sorting.WordCount   => wordCountLabel
                  }
                }
              )
            ),
            tbody(renderTable(sortBy, files))
          )
        )
      )
    ).render
    "<!DOCTYPE html>\n" + new PrettyPrinter(80, 4).format(scala.xml.Utility.trim(XML.loadString(raw)))
  }

  sealed trait Sorting
  object Sorting {
    object Name        extends Sorting
    object Description extends Sorting
    object WordCount   extends Sorting
  }

  final case class File(name: String, description: String, wordCount: Int)

  private def renderTable(sortBy: Sorting, files: Set[File]) = files.toSeq
    .sortWith { case (file1, file2) =>
      sortBy match {
        case Sorting.Name        => file1.name < file2.name
        case Sorting.Description => file1.description < file2.description
        case Sorting.WordCount   => file1.wordCount < file2.wordCount
      }
    }
    .map { file =>
      tr(
        td(a(href := s"${file.name}", file.name)),
        td(file.description),
        td(file.wordCount)
      )
    }
}
