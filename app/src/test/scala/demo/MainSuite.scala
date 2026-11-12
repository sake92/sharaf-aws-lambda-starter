package demo

import ba.sake.tupson.*
import munit.FunSuite
import sttp.client4.*

class MainSuite extends FunSuite:

  private val jsonContentType = "application/json; charset=utf-8"

  private def withServer(check: (String, SyncBackend) => Unit): Unit =
    val server = Main.startServer("127.0.0.1", 0)
    try {
      val backend = DefaultSyncBackend()
      try check(s"http://127.0.0.1:${server.address.getPort}", backend)
      finally backend.close()
    } finally server.stop()

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
      assertEquals(res.header("content-type"), Some("text/plain; charset=utf-8"))
    }
  }
