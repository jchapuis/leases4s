package io.github.jchapuis.leases4s.model

import org.typelevel.literally.Literally

object literals {
  extension (inline ctx: StringContext)
    inline def ks(inline args: Any*): KubeString =
      ${ KubeLiteral('ctx, 'args) }

  object KubeLiteral extends Literally[KubeString]:
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    def validate(s: String)(using Quotes) = KubeString(s) match {
      case Some(_) => Right('{ KubeString(${ Expr(s) }).get })
      case None    => Left("Invalid KubeString literal")
    }
}
