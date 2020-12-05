/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package server
package staticcontent

import cats.effect.IO
import cats.syntax.all._
import java.nio.file.Paths
import fs2._
import org.http4s.Uri.uri
import org.http4s.headers.{
  `Accept-Encoding`,
  `Content-Encoding`,
  `Content-Type`,
  `If-Modified-Since`
}
import org.http4s.server.middleware.TranslateUri
import org.http4s.syntax.all._

class ResourceServiceSuite extends Http4sSuite with StaticContentShared {
  val config =
    ResourceService.Config[IO]("", blocker = testBlocker)
  val defaultBase = getClass.getResource("/").getPath.toString
  val routes = resourceService(config)

  test("Respect UriTranslation") {
    val app = TranslateUri("/foo")(routes).orNotFound

    {
      val req = Request[IO](uri = uri("/foo/testresource.txt"))
      Stream.eval(app(req)).flatMap(_.body.chunks).compile.lastOrError.assertEquals(testResource) *>
        app(req).map(_.status).assertEquals(Status.Ok)
    }

    {
      val req = Request[IO](uri = uri("/testresource.txt"))
      app(req).map(_.status).assertEquals(Status.NotFound)
    }
  }

  test("Serve available content") {
    val req = Request[IO](uri = Uri.fromString("/testresource.txt").yolo)
    val rb = routes.orNotFound(req)

    Stream.eval(rb).flatMap(_.body.chunks).compile.lastOrError.assertEquals(testResource) *>
      rb.map(_.status).assertEquals(Status.Ok)
  }

  test("Decodes path segments") {
    val req = Request[IO](uri = uri("/space+truckin%27.txt"))
    routes.orNotFound(req).map(_.status).assertEquals(Status.Ok)
  }

  test("Respect the path prefix") {
    val relativePath = "testresource.txt"
    val s0 = resourceService(
      ResourceService.Config[IO](
        basePath = "",
        blocker = testBlocker,
        pathPrefix = "/path-prefix"
      ))
    val file = Paths.get(defaultBase).resolve(relativePath).toFile
    val uri = Uri.unsafeFromString("/path-prefix/" + relativePath)
    val req = Request[IO](uri = uri)
    IO(file.exists()).assertEquals(true) *>
      s0.orNotFound(req).map(_.status).assertEquals(Status.Ok)
  }

  test("Return a 400 if the request tries to escape the context") {
    val relativePath = "../testresource.txt"
    val basePath = Paths.get(defaultBase).resolve("testDir")
    val file = basePath.resolve(relativePath).toFile

    val uri = Uri.unsafeFromString("/" + relativePath)
    val req = Request[IO](uri = uri)
    val s0 = resourceService(
      ResourceService.Config[IO](
        basePath = "/testDir",
        blocker = testBlocker
      ))
    IO(file.exists()).assertEquals(true) &>
      s0.orNotFound(req).map(_.status).assertEquals(Status.BadRequest)
  }

  test("Return a 400 on path traversal, even if it's inside the context") {
    val relativePath = "testDir/../testresource.txt"
    val file = Paths.get(defaultBase).resolve(relativePath).toFile

    val uri = Uri.unsafeFromString("/" + relativePath)
    val req = Request[IO](uri = uri)
    IO(file.exists()).assertEquals(true) *>
      routes.orNotFound(req).map(_.status).assertEquals(Status.BadRequest)
  }

  test(
    "Return a 404 Not Found if the request tries to escape the context with a partial base path prefix match") {
    val relativePath = "Dir/partial-prefix.txt"
    val file = Paths.get(defaultBase).resolve(relativePath).toFile

    val uri = Uri.unsafeFromString("/test" + relativePath)
    val req = Request[IO](uri = uri)
    val s0 = resourceService(
      ResourceService.Config[IO](
        basePath = "",
        blocker = testBlocker
      ))
    IO(file.exists()).assertEquals(true) *>
      s0.orNotFound(req).map(_.status).assertEquals(Status.NotFound)
  }

  test(
    "Return a 404 Not Found if the request tries to escape the context with a partial path-prefix match") {
    val relativePath = "Dir/partial-prefix.txt"
    val file = Paths.get(defaultBase).resolve(relativePath).toFile

    val uri = Uri.unsafeFromString("/test" + relativePath)
    val req = Request[IO](uri = uri)
    val s0 = resourceService(
      ResourceService.Config[IO](
        basePath = "",
        blocker = testBlocker,
        pathPrefix = "/test"
      ))
    IO(file.exists()).assertEquals(true) *>
      s0.orNotFound(req).map(_.status).assertEquals(Status.NotFound)
  }

  test("Return a 400 Not Found if the request tries to escape the context with /") {
    val absPath = Paths.get(defaultBase).resolve("testresource.txt")
    val file = absPath.toFile

    val uri = Uri.unsafeFromString("///" + absPath)
    val req = Request[IO](uri = uri)
    val s0 = resourceService(
      ResourceService.Config[IO](
        basePath = "/testDir",
        blocker = testBlocker
      ))
    IO(file.exists()).assertEquals(true) *>
      s0.orNotFound(req).map(_.status).assertEquals(Status.BadRequest)
  }

  test("Try to serve pre-gzipped content if asked to") {
    val req = Request[IO](
      uri = Uri.fromString("/testresource.txt").yolo,
      headers = Headers.of(`Accept-Encoding`(ContentCoding.gzip))
    )
    val rb = resourceService(config.copy(preferGzipped = true)).orNotFound(req)

    Stream.eval(rb).flatMap(_.body.chunks).compile.lastOrError.assertEquals(testResourceGzipped) *>
      rb.map(_.status).assertEquals(Status.Ok) *>
      rb.map(_.headers.get(`Content-Type`).map(_.mediaType))
        .assertEquals(MediaType.text.plain.some) *>
      rb.map(_.headers.get(`Content-Encoding`).map(_.contentCoding))
        .assertEquals(ContentCoding.gzip.some)
  }

  test("Fallback to un-gzipped file if pre-gzipped version doesn't exist") {
    val req = Request[IO](
      uri = Uri.fromString("/testresource2.txt").yolo,
      headers = Headers.of(`Accept-Encoding`(ContentCoding.gzip))
    )
    val rb = resourceService(config.copy(preferGzipped = true)).orNotFound(req)

    Stream.eval(rb).flatMap(_.body.chunks).compile.lastOrError.assertEquals(testResource) *>
      rb.map(_.status).assertEquals(Status.Ok)
    rb.map(_.headers.get(`Content-Type`).map(_.mediaType))
      .assertEquals(MediaType.text.plain.some) *>
      rb.map(_.headers.get(`Content-Encoding`).map(_.contentCoding))
        .map(_ =!= ContentCoding.gzip.some)
        .assertEquals(true)
  }

  test("Generate non on missing content") {
    val req = Request[IO](uri = Uri.fromString("/testresource.txtt").yolo)
    routes.orNotFound(req).map(_.status).assertEquals(Status.NotFound)
  }

  test("Not send unmodified files") {
    val req = Request[IO](uri = uri("/testresource.txt"))
      .putHeaders(`If-Modified-Since`(HttpDate.MaxValue))

    runReq(req)._2.status === Status.NotModified
  }

  test("doesn't crash on /") {
    routes.orNotFound(Request[IO](uri = uri("/"))).map(_.status).assertEquals(Status.NotFound)
  }
}
