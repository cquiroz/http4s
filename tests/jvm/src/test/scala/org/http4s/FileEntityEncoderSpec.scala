package org.http4s

import scala.concurrent.Future

import java.io.{StringReader, ByteArrayInputStream, FileWriter, File}
import java.nio.charset.StandardCharsets

import cats._
import fs2._
import org.http4s.headers._

class FileEntityEncoderSpec extends Http4sSpec with FileEntityEncoder {
  "FileEntityEncoder" should {
    "render files" in {
      val tmpFile = File.createTempFile("http4s-test-", ".txt")
      try {
        val w = new FileWriter(tmpFile)
        try w.write("render files test")
        finally w.close()
        writeToString(tmpFile) must_== "render files test"
      }
      finally {
        tmpFile.delete()
        ()
      }
    }

    "render input streams" in {
      val inputStream = Eval.always(new ByteArrayInputStream("input stream".getBytes(StandardCharsets.UTF_8)))
      writeToString(inputStream) must_== "input stream"
    }

  }
}

