package demo

import cats.effect.IO
import cats.effect.IOApp
import ba.sake.querson.QueryStringRW
import ba.sake.sharaf.*
import ba.sake.sharaf.http4s.*
import ba.sake.tupson.JsonRW
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

case class Health(status: String) derives JsonRW
case class HelloRequest(name: String) derives JsonRW
case class HelloResponse(greeting: String) derives JsonRW
case class HelloParams(greeting: Option[String]) derives QueryStringRW

object Main extends IOApp.Simple:

  private val jsonContentType = "application/json; charset=utf-8"

  val routes = Routes:
    case GET -> Path("health") =>
      Response.withBody(Health("ok")).settingHeader("Content-Type", jsonContentType)

    case POST -> Path("hello") =>
      val body = Request.current.bodyJson[HelloRequest]
      val params = Request.current.queryParams[HelloParams]
      val greeting = params.greeting.getOrElse("Hello")
      Response
        .withBody(HelloResponse(s"$greeting, ${body.name}!"))
        .settingHeader("Content-Type", jsonContentType)

  private val jsonErrors: ExceptionMapper =
    ExceptionMapper.json.andThen(_.settingHeader("Content-Type", jsonContentType))

  given LoggerFactory[IO] = NoOpFactory[IO]

  private[demo] val httpApp =
    SharafHttpApp(SharafHandler.exceptions(SharafHandler.routes(routes), jsonErrors))

  private def configuredPort: Port =
    val portNumber = sys.env.get("PORT").map(_.toInt).getOrElse(8080)
    Port.fromInt(portNumber).getOrElse(throw IllegalArgumentException(s"Invalid PORT: $portNumber"))

  def run: IO[Unit] =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(configuredPort)
      .withHttpApp(httpApp)
      .build
      .evalTap(server => IO.println(s"Server started at ${server.address}"))
      .useForever
