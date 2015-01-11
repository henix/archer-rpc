package henix.archer

import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.annotation.StaticAnnotation
import scala.concurrent.Future
import scala.reflect.NameTransformer
import scala.reflect.macros.blackbox.Context

class RpcExport extends StaticAnnotation

case class RpcMethodCall(mod: String, func: String, params: JsObject) {
  override def toString = s"$mod.$func${params.compactPrint}"
}

class InputException(msg: String) extends RuntimeException(msg)
class UpstreamException(msg: String) extends RuntimeException(msg)

object RpcMacros {

  /**
   * Modified from ProductFormats.fromField
   */
  def fromField[T](obj: JsObject, fieldName: String)(implicit reader: JsonReader[T]): T = {
    obj.fields.get(fieldName) match {
      case Some(fieldValue) => reader.read(fieldValue)
      case None => if (reader.isInstanceOf[OptionFormat[_]]) None.asInstanceOf[T] else throw new DeserializationException("rpc.param.missing: " + fieldName)
    }
  }

  def genModule_impl[T: c.WeakTypeTag](c: Context): c.Expr[Map[String, RpcMethodCall => Future[JsValue]]] = {
    import c.universe._

    val t = c.weakTypeOf[T]
    for (m <- t.decls) { // bug: SI-7424
      m.typeSignature // force loading member's signature
      m.annotations.foreach(_.tree.tpe) // force loading all the annotations
    }

    val _RpcMacros = c.symbolOf[RpcMacros.type].asClass.module
    val _Future = c.symbolOf[Future.type].asClass.module

    println(t.termSymbol.name.toString + " {")
    val funcdefs: List[Tree] = t.decls.collect({ case m: MethodSymbol => m }).filter(m => m.isPublic && m.annotations.exists(_.tree.tpe =:= c.typeOf[RpcExport])).map({ method =>
      val params: List[Symbol] = method.paramLists.head
      val x_s: Seq[Tree] = if (params.length == 1) {
        Seq(Ident(TermName("x")))
      } else {
        for (i <- 1 to params.length) yield Select(Ident(TermName("x")), TermName("_" + i))
      }
      val fromFieldApplies: List[Tree] = params.map(s => q"${_RpcMacros}.fromField[${s.typeSignature}](params, ${s.name.toString})")
      val methodName = NameTransformer.decode(method.name.toString) // 还原特殊字符
      val mapOrFlat = if (method.returnType <:< c.typeOf[Future[_]]) TermName("flatMap") else TermName("map")
      println("  " + methodName + "(" + params.map(s => s.name.toString + ": " + s.typeSignature.toString).mkString(", ") + ")")
      q"""
        $methodName -> { implicit methodCall: ${c.weakTypeOf[RpcMethodCall]} =>
          val params = methodCall.params
          ${_Future} {
            (..$fromFieldApplies)
          } recoverWith {
            case e: ${c.weakTypeOf[DeserializationException]} => ${_Future}.failed(new ${c.weakTypeOf[InputException]}(e.getMessage))
          } $mapOrFlat { x =>
            ${t.termSymbol}.${method.name.toTermName}(..$x_s)
          } map { _.toJson }
        }
       """
    }).toList
    println("}")
    val f = c.Expr[Map[String, RpcMethodCall => Future[JsValue]]](q"Map(..$funcdefs)")
    f
  }
}
