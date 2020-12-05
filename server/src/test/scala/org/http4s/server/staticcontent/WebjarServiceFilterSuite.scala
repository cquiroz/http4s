/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.server.staticcontent

import cats.effect.IO
import org.http4s._
import org.http4s.Method.GET
import org.http4s.server.staticcontent.WebjarService.Config

class WebjarServiceFilterSuite extends Http4sSuite with StaticContentShared {
  def routes: HttpRoutes[IO] =
    webjarService(
      Config(
        filter = webjar =>
          webjar.library == "test-lib" && webjar.version == "1.0.0" && webjar.asset == "testresource.txt",
        blocker = testBlocker
      )
    )

  test("Return a 200 Ok file") {
    val req = Request[IO](GET, Uri(path = "/test-lib/1.0.0/testresource.txt"))
    val rb = runReq(req)

    assertEquals(rb._1, testWebjarResource)
    assertEquals(rb._2.status, Status.Ok)
  }

  test("Not find filtered asset") {
    val req = Request[IO](GET, Uri(path = "/test-lib/1.0.0/sub/testresource.txt"))
    val rb = runReq(req)

    assertEquals(rb._2.status, Status.NotFound)
  }
}
