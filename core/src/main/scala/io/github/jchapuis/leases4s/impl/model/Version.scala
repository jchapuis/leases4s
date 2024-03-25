package io.github.jchapuis.leases4s.impl.model

import scala.language.implicitConversions

private[impl] final case class Version(value: String) extends AnyVal

private[impl] object Version {

  /** The version of a lease that has not been acquired yet
    * - this is necessary to enable concurrency control on lease creation
    */
  val Nil: Version = Version("nil")

  implicit def toStr(version: Version): String = version.value
}
