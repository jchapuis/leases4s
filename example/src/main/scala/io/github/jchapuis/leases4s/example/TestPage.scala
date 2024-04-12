package io.github.jchapuis.leases4s.example

import io.github.jchapuis.leases4s.example.services.IndexPage
import io.github.jchapuis.leases4s.example.services.IndexPage.File

import java.io.PrintWriter

@main def test: Unit =
  val page = IndexPage.render(
    IndexPage.Sorting.Name,
    Set(File("foo", "bar", 1), File("baz", "qux", 2), File("quux", "corge", 3))
  )
  val writer = new PrintWriter("index.html")
  writer.println(page)
  writer.close()
