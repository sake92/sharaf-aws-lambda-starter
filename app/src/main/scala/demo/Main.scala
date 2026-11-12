package demo

import java.net.InetSocketAddress
import ba.sake.potateh.{Server, ServerConfig}
import ba.sake.querson.QueryStringRW
import ba.sake.sharaf.*
import ba.sake.sharaf.exceptions.NotFoundException
import ba.sake.sharaf.potateh.PotatehSharafServer
import ba.sake.tupson.JsonRW

case class Health(status: String) derives JsonRW
case class HelloRequest(name: String) derives JsonRW
case class HelloResponse(greeting: String) derives JsonRW
case class HelloParams(greeting: Option[String]) derives QueryStringRW

object Main:

  val routes = Routes:
    case GET -> Path("health") =>
      Response.withBody(Health("ok"))

    case POST -> Path("hello") =>
      val body = Request.current.bodyJson[HelloRequest]
      val params = Request.current.queryParams[HelloParams]
      val greeting = params.greeting.getOrElse("Hello")
      Response.withBody(HelloResponse(s"$greeting, ${body.name}!"))

  private[demo] def startServer(host: String, port: Int): Server =
    val notFoundHandler: SharafHandler = _ => throw NotFoundException("Route")
    val handler = SharafHandler.exceptions(
      SharafHandler.routes(routes, notFoundHandler),
      ExceptionMapper.json
    )
    val config = ServerConfig.Default.withBindAddress(InetSocketAddress(host, port))
    PotatehSharafServer.start(config, handler)

  private def configuredPort: Int =
    val portNumber = sys.env.get("PORT").map(_.toInt).getOrElse(8080)
    require(portNumber >= 0 && portNumber <= 65535, s"Invalid PORT: $portNumber")
    portNumber

  def main(args: Array[String]): Unit =
    val port = configuredPort
    val server = startServer("0.0.0.0", port)
    val shutdownHook = Thread(() => server.stop(), "server-shutdown")
    try {
      Runtime.getRuntime.addShutdownHook(shutdownHook)
      println(s"Server started at ${server.address}")
    } catch {
      case e: Throwable =>
        server.stop()
        throw e
    }
