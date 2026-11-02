package demo

import ba.sake.tupson.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.client.Client
import org.http4s.{Method, Request, Response, Uri}
import org.typelevel.ci.CIString

class MainSuite extends FunSuite:

  private val client = Client.fromHttpApp(Main.httpApp)
  private val jsonContentType = "application/json; charset=utf-8"

  private def request(method: Method, path: String, body: Option[String] = None): Request[IO] =
    val base = Request[IO](method = method, uri = Uri.unsafeFromString(s"http://localhost$path"))
    body.fold(base)(base.withEntity)

  private def run(request: Request[IO])(check: Response[IO] => IO[Unit]): Unit =
    client.run(request).use(check).unsafeRunSync()

  private def contentType(response: Response[IO]): Option[String] =
    response.headers.headers.find(_.name == CIString("Content-Type")).map(_.value)

  test("GET /health returns JSON") {
    run(request(Method.GET, "/health")) { response =>
      response.bodyText.compile.string.map { body =>
        assertEquals(response.status.code, 200)
        assertEquals(contentType(response), Some(jsonContentType))
        assertEquals(body.parseJson[Health], Health("ok"))
      }
    }
  }

  test("POST /hello returns a greeting") {
    run(request(Method.POST, "/hello", Some(HelloRequest("Sharaf").toJson))) { response =>
      response.bodyText.compile.string.map { body =>
        assertEquals(response.status.code, 200)
        assertEquals(contentType(response), Some(jsonContentType))
        assertEquals(body.parseJson[HelloResponse], HelloResponse("Hello, Sharaf!"))
      }
    }
  }

  test("POST /hello uses the greeting query parameter") {
    run(request(Method.POST, "/hello?greeting=Hi", Some(HelloRequest("Sharaf").toJson))) { response =>
      response.bodyText.compile.string.map { body =>
        assertEquals(response.status.code, 200)
        assertEquals(contentType(response), Some(jsonContentType))
        assertEquals(body.parseJson[HelloResponse], HelloResponse("Hi, Sharaf!"))
      }
    }
  }

  test("invalid JSON body returns a JSON 400") {
    run(request(Method.POST, "/hello", Some("""{"nope":1}"""))) { response =>
      response.bodyText.compile.string.map { body =>
        assertEquals(response.status.code, 400)
        assertEquals(contentType(response), Some(jsonContentType))
      }
    }
  }

  test("unknown route returns a plain-text 404") {
    run(request(Method.GET, "/does-not-exist")) { response =>
      response.bodyText.compile.string.map { body =>
        assertEquals(response.status.code, 404)
        assertEquals(contentType(response), None)
      }
    }
  }
