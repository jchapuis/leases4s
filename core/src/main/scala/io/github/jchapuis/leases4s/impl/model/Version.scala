package io.github.jchapuis.leases4s.impl.model

import scala.language.implicitConversions

private[impl] final case class Version(value: String) extends AnyVal

private[impl] object Version {
  implicit def toStr(version: Version): String = version.value
}
