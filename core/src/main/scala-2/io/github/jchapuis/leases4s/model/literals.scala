package io.github.jchapuis.leases4s.model

import org.typelevel.literally.Literally

import scala.language.experimental.macros

object literals {
  implicit class KsLiteral(val sc: StringContext) extends AnyVal {
    def ks(args: Any*): KubeString = macro KubeStringLiteral.make
  }

  object KubeStringLiteral extends Literally[KubeString] {
    def validate(c: Context)(s: String): Either[String, c.Expr[KubeString]] = {
      import c.universe.*
      KubeString(s) match {
        case Some(_) => Right(c.Expr(q"io.github.jchapuis.leases4s.model.KubeString($s).get"))
        case None    => Left("Invalid KubeString literal")
      }
    }

    def make(c: Context)(args: c.Expr[Any]*): c.Expr[KubeString] = apply(c)(args*)
  }
}
