package org.http4s.multipart

import java.io.{File, FileInputStream, InputStream}
import java.net.URL

import cats.Eq
import cats.implicits._
import fs2.{Stream, Task}
// import fs2.io.readInputStream
import fs2.text.utf8Encode
import org.http4s.{EmptyBody, Header, Headers}
import org.http4s.headers.`Content-Disposition`
import org.http4s.util.CaseInsensitiveString
import scodec.bits.ByteVector

final case class Part(headers: Headers, body: Stream[Task, Byte]) {
  def name: Option[CaseInsensitiveString] = headers.get(`Content-Disposition`).map(_.name)
}

object Part {
  private val ChunkSize = 8192

  val empty: Part =
    Part(Headers.empty, EmptyBody)

  def formData(name: String, value: String, headers: Header*): Part =
    Part(`Content-Disposition`("form-data", Map("name" -> name)) +: headers,
      Stream.emit(value).covary[Task].through(utf8Encode))

  def fileData(name: String, file: File, headers: Header*): Part =
    fileData(name, file.getName, new FileInputStream(file), headers:_*)

  def fileData(name: String, resource: URL, headers: Header*): Part =
    fileData(name, resource.getPath.split("/").last, resource.openStream(), headers:_*)

  private def fileData(name: String, filename: String, in: => InputStream, headers: Header*): Part = {
    Part(`Content-Disposition`("form-data", Map("name" -> name, "filename" -> filename)) +:
      Header("Content-Transfer-Encoding", "binary") +:
      headers, EmptyBody)
      //readInputStream(Task.delay(in), ChunkSize))
  }

}
