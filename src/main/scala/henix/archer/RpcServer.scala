package henix.archer

import java.nio.charset.StandardCharsets

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelInitializer, ChannelOption}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.{StringEncoder, StringDecoder}
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue

import scala.concurrent.{ExecutionContext, Future}
import scala.language.experimental.macros

object Rpc {

  implicit val RpcMethodCall_format = jsonFormat3(RpcMethodCall)

  def genModule[T]: Map[String, RpcMethodCall => Future[JsValue]] = macro RpcMacros.genModule_impl[T]
}

class RpcServer(mods: Map[String, Map[String, RpcMethodCall => Future[JsValue]]], host: String, port: Int)(implicit execctx: ExecutionContext) {

  private val bossGroup = new NioEventLoopGroup(1)
  private val workerGroup = new NioEventLoopGroup()

  private val dispatcher = new RpcDispatcher(mods)

  private val b = new ServerBootstrap()
  b.group(bossGroup, workerGroup)
    .channel(classOf[NioServerSocketChannel])
    .option(ChannelOption.SO_TIMEOUT, int2Integer(5 * 1000))
    .option(ChannelOption.TCP_NODELAY, boolean2Boolean(true))
    .handler(new LoggingHandler(LogLevel.DEBUG))
    .childHandler(new ChannelInitializer[SocketChannel]() {
    override def initChannel(ch: SocketChannel) {
      val p = ch.pipeline()
      p.addLast(new LineBasedFrameDecoder(2048))
      p.addLast(new StringDecoder(StandardCharsets.UTF_8))
      p.addLast(new StringEncoder(StandardCharsets.UTF_8))
      p.addLast(dispatcher)
    }
  })

  private val f = b.bind(host, port).sync()

  def join(): Unit = {
    f.channel().closeFuture().sync()
  }

  def stop(): Unit = {
    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
  }
}
