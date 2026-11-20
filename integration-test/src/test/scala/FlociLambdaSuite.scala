import java.net.URI
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import io.floci.testcontainers.FlociContainer
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.*
import sttp.client4.quick.*

/** HTTP client -> Floci Function URL -> Lambda Web Adapter -> Sharaf (the same image as the AWS deployment). */
class FlociLambdaSuite extends munit.FunSuite {

  override val munitTimeout: Duration = 5.minutes

  // Floci is pinned; it mounts the Docker socket itself to start the Lambda container.
  private val floci = FlociContainer("floci/floci:2.3.0").withDedicatedNetwork().withNetworkAliases("floci")

  // Floci runs an image that already exists on the Docker daemon as-is when its tag is AWS-shaped
  // (<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>), so no registry push is needed.
  private val imageUri = "000000000000.dkr.ecr.us-east-1.amazonaws.com/sharaf-it:floci-it"
  private val functionName = "sharaf-it"
  private val tableName = "sharaf-demo-greetings"

  private var functionUrl: String = scala.compiletime.uninitialized
  private var lambda: LambdaClient = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    // Safety: only ever talk to the container started above, with its dummy credentials.
    val buildScript = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("scripts").resolve("build-image.sh"))
      .find(Files.exists(_))
      .getOrElse(fail("scripts/build-image.sh not found"))
    val build = new ProcessBuilder(buildScript.toString, imageUri).inheritIO().start()
    assertEquals(build.waitFor(), 0, "scripts/build-image.sh failed")

    floci.start()
    val endpoint = URI.create(floci.getEndpoint)
    val region = Region.of(floci.getRegion)
    val credentials =
      StaticCredentialsProvider.create(AwsBasicCredentials.create(floci.getAccessKey, floci.getSecretKey))
    val dynamo = DynamoDbClient
      .builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentials)
      .httpClientBuilder(UrlConnectionHttpClient.builder())
      .build()
    try
      dynamo.createTable(
        CreateTableRequest
          .builder()
          .tableName(tableName)
          .attributeDefinitions(
            AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build()
          )
          .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
          .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(1L).writeCapacityUnits(1L).build())
          .build()
      )
    finally dynamo.close()

    lambda = LambdaClient
      .builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentials)
      .httpClientBuilder(UrlConnectionHttpClient.builder())
      .build()

    lambda.createFunction(
      CreateFunctionRequest
        .builder()
        .functionName(functionName)
        .packageType(PackageType.IMAGE)
        .code(FunctionCode.builder().imageUri(imageUri).build())
        .role(s"arn:aws:iam::${floci.getDefaultAccountId}:role/lambda-role")
        .timeout(30)
        .memorySize(1024)
        .environment(
          Environment
            .builder()
            .variables(
              Map(
                "AWS_ENDPOINT_URL_DYNAMODB" -> s"http://floci:${FlociContainer.PORT}"
              ).asJava
            )
            .build()
        )
        .build()
    )
    val url = lambda.createFunctionUrlConfig(
      CreateFunctionUrlConfigRequest
        .builder()
        .functionName(functionName)
        .authType(FunctionUrlAuthType.NONE)
        .build()
    )
    // Floci answers Function URLs by Host header (<id>.lambda-url.<region>.localhost) on its mapped port.
    val host = URI.create(url.functionUrl).getHost
    functionUrl = s"http://$host:${URI.create(floci.getEndpoint).getPort}"

  override def afterAll(): Unit =
    if lambda != null then
      try lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(functionName).build())
      catch case _: Exception => ()
      lambda.close()
    floci.stop()

  override def afterEach(context: AfterEach): Unit = ()

  private def dumpLogsOnFailure[T](body: => T): T =
    try body
    catch
      case e: Throwable =>
        System.err.println("--- Floci logs ---")
        System.err.println(floci.getLogs)
        throw e

  test("GET /health") {
    dumpLogsOnFailure {
      val res = quickRequest.get(uri"$functionUrl/health").readTimeout(90.seconds).send()
      assertEquals(res.code.code, 200)
      assertEquals(res.header("content-type"), Some("application/json; charset=utf-8"))
      assertEquals(res.body, """{"status":"ok"}""")
    }
  }

  test("invalid body gives 400, unknown route 404") {
    dumpLogsOnFailure {
      assertEquals(quickRequest.post(uri"$functionUrl/greetings").body("""{"nope":1}""").send().code.code, 400)
      assertEquals(quickRequest.get(uri"$functionUrl/does-not-exist").send().code.code, 404)
    }
  }

  test("POST /greetings creates a greeting that can be read from its Location") {
    dumpLogsOnFailure {
      val created = quickRequest
        .post(uri"$functionUrl/greetings")
        .contentType("application/json")
        .body("""{"greeting":"Welcome from Floci!"}""")
        .send()
      assertEquals(created.code.code, 201)
      assertEquals(created.header("content-type"), Some("application/json; charset=utf-8"))
      val location = created.header("location").getOrElse(fail("Missing Location header"))
      val id = java.util.UUID.fromString(location.stripPrefix("/greetings/")).toString
      assertEquals(location, s"/greetings/$id")
      assertEquals(created.body, s"""{"id":"$id","greeting":"Welcome from Floci!"}""")

      val res = quickRequest.get(uri"${functionUrl + location}").send()
      assertEquals(res.code.code, 200)
      assertEquals(res.header("content-type"), Some("application/json; charset=utf-8"))
      assertEquals(res.body, """{"greeting":"Welcome from Floci!"}""")
    }
  }

  test("POST /greetings rejects a blank greeting") {
    dumpLogsOnFailure {
      val res = quickRequest
        .post(uri"$functionUrl/greetings")
        .contentType("application/json")
        .body("""{"greeting":"  "}""")
        .send()
      assertEquals(res.code.code, 422)
      assertEquals(res.header("content-type"), Some("application/json; charset=utf-8"))
    }
  }

  test("GET /greetings returns 404 for a missing DynamoDB item") {
    dumpLogsOnFailure {
      val res = quickRequest.get(uri"$functionUrl/greetings/missing").send()
      assertEquals(res.code.code, 404)
      assertEquals(res.header("content-type"), Some("application/json; charset=utf-8"))
    }
  }
}
