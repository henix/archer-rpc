package henix.archer

import java.io.{PrintWriter, StringWriter}

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.TooLongFrameException
import org.slf4j.LoggerFactory
import spray.json._
import spray.json.JsonParser.ParsingException

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Sharable
class RpcDispatcher(mods: Map[String, Map[String, RpcMethodCall => Future[JsValue]]])(implicit execctx: ExecutionContext) extends SimpleChannelInboundHandler[String] {

  private lazy val logger = LoggerFactory.getLogger(classOf[RpcDispatcher])

  import Rpc.RpcMethodCall_format

  override def channelRead0(ctx: ChannelHandlerContext, msg: String) {
    // logger.debug("channel.read: {}", msg)
    val methodCall = try {
      msg.parseJson.convertTo[RpcMethodCall]
    } catch {
      case e: ParsingException => throw new InputException(e.getMessage)
      case e: DeserializationException => throw new InputException(e.getMessage)
    }
    val func = mods
      .getOrElse[Map[String, RpcMethodCall => Future[JsValue]]](methodCall.mod, throw new InputException("mod.notexist: " + methodCall.mod))
      .getOrElse(methodCall.func, throw new InputException("func.notexist: " + methodCall.func))

    val f = Future(methodCall).flatMap(func).map { res =>
      // logger.debug("{} => {}", methodCall, res)
      JsObject("type" -> JsString("Success"), "value" -> res)
    }
    f recover {
      case e: InputException =>
        JsObject("type" -> JsString("InputError"), "msg" -> JsString(e.getMessage))
      case e: UpstreamException =>
        JsObject("type" -> JsString("UpstreamError"), "msg" -> JsString(e.getMessage))
      case e: Exception =>
        val sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        JsObject("type" -> JsString("MethodCallError"), "call" -> JsString(methodCall.toString), "msg" -> JsString(sw.toString))
    } onComplete {
      case Success(reply) =>
        // logger.debug("{} => {}", methodCall, reply.compactPrint)
        ctx.writeAndFlush(reply.compactPrint + "\n")
      case Failure(e) =>
        logger.error(ctx.name(), e)
        ctx.close()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    if (cause.isInstanceOf[TooLongFrameException] || cause.isInstanceOf[InputException]) {
      logger.warn("input.error: {}", cause.getMessage)
      val reply = JsObject("type" -> JsString("InputError"), "msg" -> JsString(cause.getMessage))
      ctx.writeAndFlush(reply.compactPrint + "\n")
    } else {
      logger.error(ctx.name(), cause)
      ctx.close()
    }
  }
}
