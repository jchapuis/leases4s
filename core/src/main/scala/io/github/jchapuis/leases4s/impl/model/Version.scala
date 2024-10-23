package io.github.jchapuis.leases4s.impl.model

import scala.language.implicitConversions

private[impl] final case class Version(value: String) extends AnyVal

private[impl] object Version {

  /** The version of a lease that has not been acquired yet
    *   - this is necessary to enable concurrency control on lease creation
    */
  val Zero: Version = Version("0")

  implicit def toStr(version: Version): String = version.value
}
