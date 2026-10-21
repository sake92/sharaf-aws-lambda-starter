package demo

import java.net.ServerSocket
import ba.sake.sharaf.*
import ba.sake.sharaf.undertow.UndertowSharafServer
import ba.sake.tupson.*
import sttp.client4.quick.*

class MainSuite extends munit.FunSuite:

  private val port =
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  private val baseUrl = s"http://localhost:$port"
  private val server = UndertowSharafServer("localhost", port, Main.routes, exceptionMapper = ExceptionMapper.json)

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit = server.stop()

  test("GET /health") {
    val res = quickRequest.get(uri"$baseUrl/health").send()
    assertEquals(res.code.code, 200)
    assertEquals(res.body.parseJson[Health], Health("ok"))
  }

  test("POST /hello") {
    val res = quickRequest.post(uri"$baseUrl/hello").body(HelloRequest("Sharaf").toJson).send()
    assertEquals(res.code.code, 200)
    assertEquals(res.body.parseJson[HelloResponse], HelloResponse("Hello, Sharaf!"))
  }

  test("POST /hello with greeting query param") {
    val res = quickRequest.post(uri"$baseUrl/hello?greeting=Hi").body(HelloRequest("Sharaf").toJson).send()
    assertEquals(res.code.code, 200)
    assertEquals(res.body.parseJson[HelloResponse], HelloResponse("Hi, Sharaf!"))
  }
