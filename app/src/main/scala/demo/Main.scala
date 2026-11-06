package demo

import java.net.InetSocketAddress
import java.util.concurrent.{Executor, Executors, TimeUnit}
import com.sun.net.httpserver.HttpServer
import ba.sake.querson.QueryStringRW
import ba.sake.sharaf.*
import ba.sake.sharaf.jdkhttp.SharafJdkHttpHandler
import ba.sake.tupson.JsonRW

case class Health(status: String) derives JsonRW
case class HelloRequest(name: String) derives JsonRW
case class HelloResponse(greeting: String) derives JsonRW
case class HelloParams(greeting: Option[String]) derives QueryStringRW

object Main:

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

  private[demo] def createServer(host: String, port: Int, executor: Executor): HttpServer =
    val notFoundHandler: SharafHandler = context =>
      SharafHandler.DefaultNotFoundHandler.handle(context).removingHeader("Content-Type")
    val handler = SharafHandler.exceptions(SharafHandler.routes(routes, notFoundHandler), jsonErrors)
    val server = HttpServer.create(InetSocketAddress(host, port), 0)
    server.createContext("/", SharafJdkHttpHandler(handler))
    server.setExecutor(executor)
    server

  private def configuredPort: Int =
    val portNumber = sys.env.get("PORT").map(_.toInt).getOrElse(8080)
    require(portNumber >= 0 && portNumber <= 65535, s"Invalid PORT: $portNumber")
    portNumber

  def main(args: Array[String]): Unit =
    val port = configuredPort
    val executor = Executors.newFixedThreadPool(10)
    try {
      val server = createServer("0.0.0.0", port, executor)
      def stop(): Unit =
        try server.stop(0)
        finally
          executor.shutdownNow()
          executor.awaitTermination(5, TimeUnit.SECONDS)
      val shutdownHook = Thread(() => stop(), "server-shutdown")
      Runtime.getRuntime.addShutdownHook(shutdownHook)
      try {
        server.start()
        println(s"Server started at ${server.getAddress}")
      } catch {
        case e: Throwable =>
          Runtime.getRuntime.removeShutdownHook(shutdownHook)
          stop()
          throw e
      }
    } catch {
      case e: Throwable =>
        executor.shutdownNow()
        throw e
    }
