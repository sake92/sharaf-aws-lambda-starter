package demo

import ba.sake.tupson.*
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.*
import demo.Main.given
import munit.FunSuite
import org.http4s.ember.server.EmberServerBuilder
import sttp.client4.*

class MainSuite extends FunSuite:

  private val jsonContentType = "application/json; charset=utf-8"

  private def withServer(check: (String, SyncBackend) => Unit): Unit =
    val resources = for
      server <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(Main.httpApp)
        .build
      backend <- Resource.make(IO(DefaultSyncBackend()))(backend => IO(backend.close()))
    yield (s"http://127.0.0.1:${server.address.port.value}", backend)

    resources.use { case (baseUrl, backend) => IO.blocking(check(baseUrl, backend)) }.unsafeRunSync()

  test("GET /health returns JSON") {
    withServer { (baseUrl, backend) =>
      val res = quickRequest.get(uri"$baseUrl/health").send(backend)
      assertEquals(res.code.code, 200)
      assertEquals(res.header("content-type"), Some(jsonContentType))
      assertEquals(res.body.parseJson[Health], Health("ok"))
    }
  }

  test("POST /hello returns a greeting") {
    withServer { (baseUrl, backend) =>
      val res = quickRequest.post(uri"$baseUrl/hello").body(HelloRequest("Sharaf").toJson).send(backend)
      assertEquals(res.code.code, 200)
      assertEquals(res.header("content-type"), Some(jsonContentType))
      assertEquals(res.body.parseJson[HelloResponse], HelloResponse("Hello, Sharaf!"))
    }
  }

  test("POST /hello uses the greeting query parameter") {
    withServer { (baseUrl, backend) =>
      val res = quickRequest.post(uri"$baseUrl/hello?greeting=Hi").body(HelloRequest("Sharaf").toJson).send(backend)
      assertEquals(res.code.code, 200)
      assertEquals(res.header("content-type"), Some(jsonContentType))
      assertEquals(res.body.parseJson[HelloResponse], HelloResponse("Hi, Sharaf!"))
    }
  }

  test("invalid JSON body returns a JSON 400") {
    withServer { (baseUrl, backend) =>
      val res = quickRequest.post(uri"$baseUrl/hello").body("""{"nope":1}""").send(backend)
      assertEquals(res.code.code, 400)
      assertEquals(res.header("content-type"), Some(jsonContentType))
    }
  }

  test("unknown route returns a plain-text 404") {
    withServer { (baseUrl, backend) =>
      val res = quickRequest.get(uri"$baseUrl/does-not-exist").send(backend)
      assertEquals(res.code.code, 404)
      assertEquals(res.header("content-type"), None)
    }
  }
