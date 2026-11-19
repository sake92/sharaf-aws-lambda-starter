package demo

import ba.sake.querson.QueryStringRW
import ba.sake.sharaf.*
import ba.sake.sharaf.exceptions.NotFoundException
import ba.sake.sharaf.potateh.PotatehSharafServer
import ba.sake.tupson.JsonRW

case class Health(status: String) derives JsonRW
case class HelloRequest(name: String) derives JsonRW
case class HelloResponse(greeting: String) derives JsonRW
case class HelloParams(greeting: Option[String]) derives QueryStringRW

object Main {

  val routes = Routes:
    case GET -> Path("health") =>
      Response.withBody(Health("ok"))

    case POST -> Path("hello") =>
      val body = Request.current.bodyJson[HelloRequest]
      val params = Request.current.queryParams[HelloParams]
      val greeting = params.greeting.getOrElse("Hello")
      Response.withBody(HelloResponse(s"$greeting, ${body.name}!"))

  private[demo] def startServer(host: String, port: Int): PotatehSharafServer =
    val notFoundHandler: SharafHandler = _ => throw NotFoundException("Route")
    val server =
      PotatehSharafServer(routes, host, port, exceptionMapper = ExceptionMapper.json, notFoundHandler = notFoundHandler)
    server.start()
    server

}

@main def runServer(): Unit =
  val port = sys.env.get("PORT").map(_.toInt).getOrElse(8080)
  require(port >= 0 && port <= 65535, s"Invalid PORT: $port")
  val server = Main.startServer("0.0.0.0", port)
  println(s"Server started on port ${server.port}")
