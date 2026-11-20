package demo

import ba.sake.querson.QueryStringRW
import ba.sake.sharaf.*
import ba.sake.sharaf.exceptions.NotFoundException
import ba.sake.sharaf.potateh.PotatehSharafServer
import ba.sake.tupson.JsonRW
import ba.sake.validson.Validator
import java.time.Duration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

case class Health(status: String) derives JsonRW
case class HelloRequest(name: String) derives JsonRW
case class HelloResponse(greeting: String) derives JsonRW
case class CreateGreetingRequest(greeting: String) derives JsonRW
object CreateGreetingRequest:
  given Validator[CreateGreetingRequest] = Validator
    .derived[CreateGreetingRequest]
    .notBlank(_.greeting)
    .maxLength(_.greeting, 256)

case class CreatedGreeting(id: String, greeting: String) derives JsonRW
case class HelloParams(greeting: Option[String]) derives QueryStringRW

private def routes(
    findGreeting: String => Option[HelloResponse],
    createGreeting: String => CreatedGreeting
) = Routes {
  case GET -> Path("health") =>
    Response.withBody(Health("ok"))

  case POST -> Path("hello") =>
    val body = Request.current.bodyJson[HelloRequest]
    val params = Request.current.queryParams[HelloParams]
    val greeting = params.greeting.getOrElse("Hello")
    Response.withBody(HelloResponse(s"$greeting, ${body.name}!"))

  case GET -> Path("greetings", id) =>
    Response.withBodyOpt(findGreeting(id), "Greeting")

  case POST -> Path("greetings") =>
    val body = Request.current.bodyJsonValidated[CreateGreetingRequest]
    val created = createGreeting(body.greeting)
    Response
      .withStatus(sttp.model.StatusCode.Created)
      .settingHeader("Location", s"/greetings/${created.id}")
      .withBody(created)
}

private[demo] def createServer(
    host: String,
    port: Int,
    findGreeting: String => Option[HelloResponse],
    createGreeting: String => CreatedGreeting
): PotatehSharafServer =
  val notFoundHandler: SharafHandler = _ => throw NotFoundException("Route")
  PotatehSharafServer(
    routes(findGreeting, createGreeting),
    host,
    port,
    exceptionMapper = ExceptionMapper.json,
    notFoundHandler = notFoundHandler
  )

@main def runServer(): Unit =
  val port = sys.env.get("PORT").map(_.toInt).getOrElse(8080)
  require(port >= 0 && port <= 65535, s"Invalid PORT: $port")
  val dynamo = DynamoDbClient
    .builder()
    .httpClientBuilder(UrlConnectionHttpClient.builder())
    .overrideConfiguration(config => config.apiCallTimeout(Duration.ofSeconds(5)))
    .build()
  val greetings = new GreetingsService(dynamo)
  val server = createServer("0.0.0.0", port, greetings.findGreeting, greetings.createGreeting)
  sys.addShutdownHook {
    try server.stop()
    finally dynamo.close()
  }
  server.start()
  println(s"Server started on port ${server.port}")
