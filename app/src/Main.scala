package demo

import ba.sake.querson.QueryStringRW
import ba.sake.sharaf.*
import ba.sake.sharaf.undertow.UndertowSharafServer
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

  def main(args: Array[String]): Unit =
    val port = sys.env.get("PORT").map(_.toInt).getOrElse(8080)
    UndertowSharafServer("0.0.0.0", port, routes, exceptionMapper = ExceptionMapper.json).start()
    println(s"Server started at http://0.0.0.0:$port")
