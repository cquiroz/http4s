package com.example.http4s.blaze

import org.http4s._
import org.http4s.Http4s._
import org.http4s.client.blaze.{defaultClient => client}

object ClientPostExample extends App {
  val req = Request(method = Method.POST, uri = uri("https://duckduckgo.com/"))
    .withBody(UrlForm(Map("q" -> Seq("http4s"))))
  val responseBody = client(req).as[String]
  println(responseBody.run)
}
