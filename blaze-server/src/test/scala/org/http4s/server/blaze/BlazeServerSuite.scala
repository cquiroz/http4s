/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package server
package blaze

import cats.syntax.all._
import cats.effect._
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import org.http4s.blaze.channel.ChannelOptions
import org.http4s.dsl.io._
import scala.concurrent.duration._
import scala.io.Source
import org.http4s.multipart.Multipart
import scala.concurrent.ExecutionContext.global

class BlazeServerSuite extends Http4sSuite {
  implicit val contextShift: ContextShift[IO] = Http4sSpec.TestContextShift

  def builder =
    BlazeServerBuilder[IO](global)
      .withResponseHeaderTimeout(1.second)

  val service: HttpApp[IO] = HttpApp {
    case GET -> Root / "thread" / "routing" =>
      val thread = Thread.currentThread.getName
      Ok(thread)

    case GET -> Root / "thread" / "effect" =>
      IO(Thread.currentThread.getName).flatMap(Ok(_))

    case req @ POST -> Root / "echo" =>
      Ok(req.body)

    case _ -> Root / "never" =>
      IO.never

    case req @ POST -> Root / "issue2610" =>
      req.decode[Multipart[IO]] { mp =>
        Ok(mp.parts.foldMap(_.body))
      }

    case _ => NotFound()
  }

  val serverR =
    builder
      .bindAny()
      .withHttpApp(service)
      .resource

  withResource(serverR) { server =>
    // This should be in IO and shifted but I'm tired of fighting this.
    def get(path: String): IO[String] = IO {
      Source
        .fromURL(new URL(s"http://127.0.0.1:${server.address.getPort}$path"))
        .getLines()
        .mkString
    }

    // This should be in IO and shifted but I'm tired of fighting this.
    def getStatus(path: String): IO[Status] = {
      val url = new URL(s"http://127.0.0.1:${server.address.getPort}$path")
      for {
        conn <- IO(url.openConnection().asInstanceOf[HttpURLConnection])
        _ = conn.setRequestMethod("GET")
        status <- IO.fromEither(Status.fromInt(conn.getResponseCode()))
      } yield status
    }

    // This too
    def post(path: String, body: String): IO[String] = IO {
      val url = new URL(s"http://127.0.0.1:${server.address.getPort}$path")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      conn.setRequestMethod("POST")
      conn.setRequestProperty("Content-Length", bytes.size.toString)
      conn.setDoOutput(true)
      conn.getOutputStream.write(bytes)
      Source.fromInputStream(conn.getInputStream, StandardCharsets.UTF_8.name).getLines().mkString
    }

    // This too
    def postChunkedMultipart(path: String, boundary: String, body: String): IO[String] =
      IO {
        val url = new URL(s"http://127.0.0.1:${server.address.getPort}$path")
        val conn = url.openConnection().asInstanceOf[HttpURLConnection]
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        conn.setRequestMethod("POST")
        conn.setChunkedStreamingMode(-1)
        conn.setRequestProperty("Content-Type", s"""multipart/form-data; boundary="$boundary"""")
        conn.setDoOutput(true)
        conn.getOutputStream.write(bytes)
        Source.fromInputStream(conn.getInputStream, StandardCharsets.UTF_8.name).getLines().mkString
      }

    test("route requests on the service executor") {
      get("/thread/routing").map(_.startsWith("http4s-spec-")).assertEquals(true)
    }

    test("execute the service task on the service executor") {
      get("/thread/effect").map(_.startsWith("http4s-spec-")).assertEquals(true)
    }

    test("be able to echo its input") {
      val input = """{ "Hello": "world" }"""
      post("/echo", input).map(_.startsWith(input)).assertEquals(true)
    }

    test("return a 503 if the server doesn't respond") {
      getStatus("/never").assertEquals(Status.ServiceUnavailable)
    }

    test("reliably handle multipart requests") {
      val body =
        """|--aa
             |Content-Disposition: form-data; name="a"
             |Content-Length: 1
             |
             |a
             |--aa--""".stripMargin.replace("\n", "\r\n")

      // This is flaky due to Blaze threading and Java connection pooling.
      (1 to 100).toList.traverse { _ =>
        postChunkedMultipart(
          "/issue2610",
          "aa",
          body
        ).assertEquals("a")
      }
    }
  }

  test("ChannelOptions should default to empty") {
    assertEquals(builder.channelOptions, ChannelOptions(Vector.empty))
  }
  test("ChannelOptions should set socket send buffer size") {
    assertEquals(builder.withSocketSendBufferSize(8192).socketSendBufferSize, Some(8192))
  }
  test("ChannelOptions should set socket receive buffer size") {
    assertEquals(builder.withSocketReceiveBufferSize(8192).socketReceiveBufferSize, Some(8192))
  }
  test("ChannelOptions should set socket keepalive") {
    assertEquals(builder.withSocketKeepAlive(true).socketKeepAlive, Some(true))
  }
  test("ChannelOptions should set socket reuse address") {
    assertEquals(builder.withSocketReuseAddress(true).socketReuseAddress, Some(true))
  }
  test("ChannelOptions should set TCP nodelay") {
    assertEquals(builder.withTcpNoDelay(true).tcpNoDelay, Some(true))
  }
  test("ChannelOptions should unset socket send buffer size") {
    assertEquals(
      builder
        .withSocketSendBufferSize(8192)
        .withDefaultSocketSendBufferSize
        .socketSendBufferSize,
      None)
  }
  test("ChannelOptions should unset socket receive buffer size") {
    assertEquals(
      builder
        .withSocketReceiveBufferSize(8192)
        .withDefaultSocketReceiveBufferSize
        .socketReceiveBufferSize,
      None)
  }
  test("ChannelOptions should unset socket keepalive") {
    assertEquals(builder.withSocketKeepAlive(true).withDefaultSocketKeepAlive.socketKeepAlive, None)
  }
  test("ChannelOptions should unset socket reuse address") {
    assertEquals(
      builder
        .withSocketReuseAddress(true)
        .withDefaultSocketReuseAddress
        .socketReuseAddress,
      None)
  }
  test("ChannelOptions should unset TCP nodelay") {
    assertEquals(builder.withTcpNoDelay(true).withDefaultTcpNoDelay.tcpNoDelay, None)
  }
  test("ChannelOptions should overwrite keys") {
    assertEquals(
      builder
        .withSocketSendBufferSize(8192)
        .withSocketSendBufferSize(4096)
        .socketSendBufferSize,
      Some(4096))
  }
}
