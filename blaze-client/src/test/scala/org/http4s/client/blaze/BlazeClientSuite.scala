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

package org.http4s.client
package blaze

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.syntax.all._
import fs2.Stream
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.servlet.ServletOutputStream
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import org.http4s._
import org.http4s.blaze.util.TickWheelExecutor
import org.http4s.syntax.all._
import org.http4s.client.testroutes.GetRoutes
import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.ExecutionContext
import cats.data.Nested

class BlazeClientSuite extends Http4sSuite {
  val testExecutionContext: ExecutionContext = Http4sSpec.TestExecutionContext
  val tickWheel = new TickWheelExecutor(tick = 50.millis)

  private val timeout = 30.seconds

  def mkClient(
      maxConnectionsPerRequestKey: Int,
      maxTotalConnections: Int = 5,
      responseHeaderTimeout: Duration = 30.seconds,
      requestTimeout: Duration = 45.seconds,
      chunkBufferMaxSize: Int = 1024,
      sslContextOption: Option[SSLContext] = Some(bits.TrustingSslContext)
  ) =
    BlazeClientBuilder[IO](testExecutionContext)
      .withSslContextOption(sslContextOption)
      .withCheckEndpointAuthentication(false)
      .withResponseHeaderTimeout(responseHeaderTimeout)
      .withRequestTimeout(requestTimeout)
      .withMaxTotalConnections(maxTotalConnections)
      .withMaxConnectionsPerRequestKey(Function.const(maxConnectionsPerRequestKey))
      .withChunkBufferMaxSize(chunkBufferMaxSize)
      .withScheduler(scheduler = tickWheel)
      .resource

  private def testServlet =
    new HttpServlet {
      override def doGet(req: HttpServletRequest, srv: HttpServletResponse): Unit =
        GetRoutes.getPaths.get(req.getRequestURI) match {
          case Some(resp) =>
            srv.setStatus(resp.status.code)
            resp.headers.foreach { h =>
              srv.addHeader(h.name.toString, h.value)
            }

            val os: ServletOutputStream = srv.getOutputStream

            val writeBody: IO[Unit] = resp.body
              .evalMap { byte =>
                IO(os.write(Array(byte)))
              }
              .compile
              .drain
            val flushOutputStream: IO[Unit] = IO(os.flush())
            (writeBody *> flushOutputStream).unsafeRunSync()

          case None => srv.sendError(404)
        }

      override def doPost(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
        resp.setStatus(Status.Ok.code)
        req.getInputStream.close()
      }
    }

  // "Blaze Http1Client" should {
  withResource(
    (
      JettyScaffold[IO](5, false, testServlet),
      JettyScaffold[IO](1, true, testServlet)
    ).tupled) {
    case (
          jettyServer,
          jettySslServer
        ) =>
      val addresses = jettyServer.addresses
      val sslAddress = jettySslServer.addresses.head

      test(
        "Blaze Http1Client raise error NoConnectionAllowedException if no connections are permitted for key") {
        val name = sslAddress.getHostName
        val port = sslAddress.getPort
        val u = Uri.fromString(s"https://$name:$port/simple").yolo
        val resp = IO.race(IO.sleep(timeout), mkClient(0).use(_.expect[String](u).attempt))
        resp.assertEquals(
          Right(Left(NoConnectionAllowedException(RequestKey(u.scheme.get, u.authority.get)))))
      }

      test("Blaze Http1Client make simple https requests") {
        val name = sslAddress.getHostName
        val port = sslAddress.getPort
        val u = Uri.fromString(s"https://$name:$port/simple").yolo
        val resp = IO.race(IO.sleep(timeout), mkClient(1).use(_.expect[String](u).attempt))
        Nested(resp).map(_.map(_.length > 0)).value.assertEquals(Right(Right(true)))
      }

      test("Blaze Http1Client reject https requests when no SSLContext is configured") {
        val name = sslAddress.getHostName
        val port = sslAddress.getPort
        val u = Uri.fromString(s"https://$name:$port/simple").yolo
        val resp = IO.race(
          IO.sleep(1.second),
          mkClient(1, sslContextOption = None)
            .use(_.expect[String](u))
            .attempt)
        resp
          .map {
            case Right(Left(_: ConnectionFailure)) => true
            case _ => false
          }
          .assertEquals(true)
      }

      test("Blaze Http1Client behave and not deadlock".flaky) {
        val hosts = addresses.map { address =>
          val name = address.getHostName
          val port = address.getPort
          Uri.fromString(s"http://$name:$port/simple").yolo
        }

        IO.race(
          IO.sleep(timeout),
          mkClient(3)
            .use { client =>
              (1 to Runtime.getRuntime.availableProcessors * 5).toList
                .parTraverse { _ =>
                  val h = hosts(Random.nextInt(hosts.length))
                  client.expect[String](h).map(_.nonEmpty)
                }
                .map(_.forall(identity))
            }
        ).assertEquals(Right(true))
      }

      test("Blaze Http1Client behave and not deadlock on failures with parTraverse".flaky) {
        IO.race(
          IO.sleep(timeout),
          mkClient(3)
            .use { client =>
              val failedHosts = addresses.map { address =>
                val name = address.getHostName
                val port = address.getPort
                Uri.fromString(s"http://$name:$port/internal-server-error").yolo
              }

              val successHosts = addresses.map { address =>
                val name = address.getHostName
                val port = address.getPort
                Uri.fromString(s"http://$name:$port/simple").yolo
              }

              val failedRequests =
                (1 to Runtime.getRuntime.availableProcessors * 5).toList.parTraverse { _ =>
                  val h = failedHosts(Random.nextInt(failedHosts.length))
                  client.expect[String](h)
                }

              val sucessRequests =
                (1 to Runtime.getRuntime.availableProcessors * 5).toList.parTraverse { _ =>
                  val h = successHosts(Random.nextInt(successHosts.length))
                  client.expect[String](h).map(_.nonEmpty)
                }

              val allRequests = for {
                _ <- failedRequests.handleErrorWith(_ => IO.unit).replicateA(5)
                r <- sucessRequests
              } yield r

              allRequests
                .flatTap(res => IO(println(res)))
                .map(_.forall(identity))
            }
        ).assertEquals(Right(true))
      }

      test("Blaze Http1Client behave and not deadlock on failures with parSequence".flaky) {
        IO.race(
          IO.sleep(timeout),
          mkClient(3)
            .use { client =>
              val failedHosts = addresses.map { address =>
                val name = address.getHostName
                val port = address.getPort
                Uri.fromString(s"http://$name:$port/internal-server-error").yolo
              }

              val successHosts = addresses.map { address =>
                val name = address.getHostName
                val port = address.getPort
                Uri.fromString(s"http://$name:$port/simple").yolo
              }

              val failedRequests =
                (1 to Runtime.getRuntime.availableProcessors * 5).toList.map { _ =>
                  val h = failedHosts(Random.nextInt(failedHosts.length))
                  client.expect[String](h)
                }.parSequence

              val sucessRequests =
                (1 to Runtime.getRuntime.availableProcessors * 5).toList.map { _ =>
                  val h = successHosts(Random.nextInt(successHosts.length))
                  client.expect[String](h).map(_.nonEmpty)
                }.parSequence

              val allRequests = for {
                _ <- failedRequests.handleErrorWith(_ => IO.unit).replicateA(5)
                r <- sucessRequests
              } yield r

              allRequests
                .map(_.forall(identity))
            }
        ).assertEquals(Right(true))
      }

      test("Blaze Http1Client obey response header timeout".flaky) {
        val address = addresses(0)
        val name = address.getHostName
        val port = address.getPort
        mkClient(1, responseHeaderTimeout = 100.millis)
          .use { client =>
            val submit = client.expect[String](Uri.fromString(s"http://$name:$port/delayed").yolo)
            submit
          }
          .intercept[TimeoutException]
      }

      test("Blaze Http1Client unblock waiting connections") {
        val address = addresses(0)
        val name = address.getHostName
        val port = address.getPort
        mkClient(1, responseHeaderTimeout = 20.seconds)
          .use { client =>
            val submit = client.expect[String](Uri.fromString(s"http://$name:$port/delayed").yolo)
            for {
              _ <- submit.start
              r <- submit.attempt
            } yield r
          }
          .map(_.isRight)
          .assertEquals(true)
      }

      test("Blaze Http1Client reset request timeout") {
        val address = addresses(0)
        val name = address.getHostName
        val port = address.getPort

        Ref[IO]
          .of(0L)
          .flatMap { _ =>
            mkClient(1, requestTimeout = 1.second).use { client =>
              val submit =
                client.status(Request[IO](uri = Uri.fromString(s"http://$name:$port/simple").yolo))
              submit *> munitTimer.sleep(2.seconds) *> submit
            }
          }
          .assertEquals(Status.Ok)
      }

      test("Blaze Http1Client drain waiting connections after shutdown") {
        val address = addresses(0)
        val name = address.getHostName
        val port = address.getPort

        val resp = mkClient(1, responseHeaderTimeout = 20.seconds)
          .use { drainTestClient =>
            drainTestClient
              .expect[String](Uri.fromString(s"http://$name:$port/delayed").yolo)
              .attempt
              .start

            val resp = drainTestClient
              .expect[String](Uri.fromString(s"http://$name:$port/delayed").yolo)
              .attempt
              .map(_.exists(_.nonEmpty))
              .start

            // Wait 100 millis to shut down
            IO.sleep(100.millis) *> resp.flatMap(_.join)
          }

        IO.race(IO.sleep(6.seconds), resp).assertEquals(Right(true))
      }

      test("Blaze Http1Client cancel infinite request on completion".flaky) {
        val address = addresses(0)
        val name = address.getHostName
        val port = address.getPort
        IO.race(
          IO.sleep(5.seconds),
          Deferred[IO, Unit]
            .flatMap { reqClosed =>
              mkClient(1, requestTimeout = 10.seconds).use { client =>
                val body = Stream(0.toByte).repeat.onFinalizeWeak(reqClosed.complete(()))
                val req = Request[IO](
                  method = Method.POST,
                  uri = Uri.fromString(s"http://$name:$port/").yolo
                ).withBodyStream(body)
                client.status(req) >> reqClosed.get
              }
            }
        ).assertEquals(Right(()))
      }

      test("Blaze Http1Client doesn't leak connection on timeout") {
        val address = addresses.head
        val name = address.getHostName
        val port = address.getPort
        val uri = Uri.fromString(s"http://$name:$port/simple").yolo

        IO.race(
          IO.sleep(5.seconds),
          mkClient(1)
            .use { client =>
              val req = Request[IO](uri = uri)
              client
                .run(req)
                .use { _ =>
                  IO.never
                }
                .timeout(250.millis)
                .attempt >>
                client.status(req)
            }
        ).assertEquals(Right(Status.Ok))
      }

      test("Blaze Http1Client call a second host after reusing connections on a first") {
        // https://github.com/http4s/http4s/pull/2546
        IO.race(
          IO.sleep(5.seconds),
          mkClient(maxConnectionsPerRequestKey = Int.MaxValue, maxTotalConnections = 5)
            .use { client =>
              val uris = addresses.take(2).map { address =>
                val name = address.getHostName
                val port = address.getPort
                Uri.fromString(s"http://$name:$port/simple").yolo
              }
              val s = Stream(
                Stream.eval(
                  client.expect[String](Request[IO](uri = uris(0)))
                )).repeat.take(10).parJoinUnbounded ++ Stream.eval(
                client.expect[String](Request[IO](uri = uris(1))))
              s.compile.lastOrError
            }
        ).assertEquals(Right("simple path"))
      }

      test("Blaze Http1Client raise a ConnectionFailure when a host can't be resolved") {
        mkClient(1)
          .use { client =>
            client.status(Request[IO](uri = uri"http://example.invalid/"))
          }
          .attempt
          .map {
            case Left(e: ConnectionFailure) =>
              e.getMessage === "Error connecting to http://example.invalid using address example.invalid:80 (unresolved: true)"
            case _ => false
          }
          .assertEquals(true)
      }
  }
}
