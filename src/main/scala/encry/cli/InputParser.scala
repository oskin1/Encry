package encry.cli

import fastparse.{all, core}

object InputParser {

  import fastparse.all._

  val ws = P( " " )

  val letter: all.Parser[Unit] =        P( lowercase | uppercase )
  val lowercase: all.Parser[Unit] =     P( CharIn('a' to 'z') )
  val uppercase: all.Parser[Unit] =     P( CharIn('A' to 'Z') )
  val digit: all.Parser[Unit] =         P( CharIn('0' to '9') )

  val stringliteral: P[String] = P( stringprefix.? ~ shortstring )
  val stringprefix: P0 = P(
    "r" | "u" | "ur" | "R" | "U" | "UR" | "Ur" | "uR" | "b" | "B" | "br" | "Br" | "bR" | "BR"
  )
  val shortstring: P[String] = P( shortstring0("'") | shortstring0("\"") )
  def shortstring0(delimiter: String): all.Parser[String] = P( delimiter ~ shortstringitem(delimiter).rep.! ~ delimiter)
  def shortstringitem(quote: String): P0 = P( shortstringchar(quote) | escapeseq )
  def shortstringchar(quote: String): P0 = P( CharsWhile(!s"\\\n${quote(0)}".contains(_)) )

  def negatable[T](p: P[T])(implicit ev: Numeric[T]): core.Parser[T, Char, String] = (("+" | "-").?.! ~ p).map {
    case ("-", i) => ev.negate(i)
    case (_, i) => i
  }

  val escapeseq: P0 = P( "\\" ~ AnyChar )

  val number = negatable[Long](P( CharIn('0' to '9').rep(min = 1).!.map(_.toLong) ))

  val IDENT: P[Ast.Identifier] = P( letter.rep ).!.map(Ast.Identifier)
  val NUMBER: P[Ast.Num] = P( intConstExpr )
  val BOOL: P[Ast.Bool] = P( trueExpr | falseExpr )
  val STRING: P[Ast.Str] = stringliteral.map(Ast.Str)

  val intConstExpr: P[Ast.Num] = P( number ).map(Ast.Num)

  val trueExpr: P[Ast.True.type] = P( "true".rep(min = 1, max = 1).! ).map(_ => Ast.True)
  val falseExpr: P[Ast.False.type] = P( "false".rep(min = 1, max = 1).! ).map(_ => Ast.False)

  val valueP: P[Ast.Value] = P( STRING | NUMBER | BOOL )

  val paramP: P[Ast.Param] = P( "-" ~ IDENT ~ "=" ~ valueP ).map { case (id, v) => Ast.Param(id, v) }

  val flagP: P[Ast.Flag] = P( "--" ~ IDENT ).map(Ast.Flag)

  val paramsP: P[Seq[Ast.Param]] = P( paramP.rep(min = 1, " ") )

  val flagsP: P[Seq[Ast.Flag]] = P( flagP.rep(min = 1, " ") )

  val commandP: P[Ast.Command] = P( IDENT ~ ws ~ IDENT ~ ws.? ~ paramsP.? ~ ws.? ~ flagsP.? ).map { case (cat, cmd, params, flags) =>
    Ast.Command(cat, cmd, params.getOrElse(List.empty).toList, flags.getOrElse(List.empty).toList)
  }
}
