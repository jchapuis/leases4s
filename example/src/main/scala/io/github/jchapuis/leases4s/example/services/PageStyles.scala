package io.github.jchapuis.leases4s.example.services

import scalatags.stylesheet.*
import scalatags.Text.all.*

object PageStyles extends StyleSheet {
  initStyleSheet()

  override def customSheetName: Option[String] = Some("page")

  val body: Cls = cls(
    fontFamily      := "Arial, sans-serif",
    margin          := 0,
    padding         := 0,
    backgroundColor := "#f0f0f0"
  )

  val container: Cls = cls(
    maxWidth        := 800.px,
    margin          := "50px auto",
    padding         := 20.px,
    backgroundColor := "#fff",
    borderRadius    := 8.px,
    boxShadow       := "0px 0px 10px rgba(0, 0, 0, 0.1)"
  )

  val heading: Cls = cls(
    textAlign := "center",
    color     := "#333"
  )

  val table: Cls = cls(
    width          := 100.pct,
    borderCollapse := "collapse",
    marginTop      := 20.px
  )

  val th: Cls = cls(
    backgroundColor := "#f2f2f2",
    padding         := 10.px,
    textAlign       := "left",
    borderBottom    := "1px solid #ddd"
  )

  val a: Cls = cls(
    textDecoration := "none",
    color          := "#f0f0f0"
  )

  val aHover: Cls = cls.hover(
    textDecoration := "underline"
  )

  val uploadLink: Cls = cls(
    display      := "block",
    textAlign    := "center",
    marginBottom := 20.px
  )

  val uploadLinkA: Cls = cls(
    padding         := "10px 20px",
    backgroundColor := "#4CAF50"
  )
}
