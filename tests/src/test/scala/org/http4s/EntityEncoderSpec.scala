package org.http4s

import scala.concurrent.Future

import java.io.{StringReader, ByteArrayInputStream, FileWriter, File}
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

import cats._
import fs2._
import org.http4s.headers._

class EntityEncoderSpec extends Http4sSpec {
  "EntityEncoder" should {
    "render strings" in {
      writeToString("pong") must_== "pong"
    }

    "calculate the content length of strings" in {
      implicitly[EntityEncoder[String]].toEntity("pong").map(_.length) must returnValue(Some(4))
    }

    "render byte arrays" in {
      val hello = "hello"
      writeToString(hello.getBytes(StandardCharsets.UTF_8)) must_== hello
    }

    "render byte buffer" in {
      val hello = "hello"
      val bb = ByteBuffer.wrap(hello.getBytes(StandardCharsets.UTF_8))
      writeToString(bb) must_== hello
    }

    "render futures" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val hello = "Hello"
      writeToString(Future(hello)) must_== hello
    }

    "render Tasks" in {
      val hello = "Hello"
      writeToString(Task.now(hello)) must_== hello
    }

    "render processes" in {
      val helloWorld = Stream("hello", "world")
      writeToString(helloWorld) must_== "helloworld"
    }

    "render bytebuffer processes" in {
      val helloWorld = Stream(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap("world".getBytes(StandardCharsets.UTF_8)))
      writeToString(helloWorld) must_== "helloworld"
    }

    "render processes with chunked transfer encoding" in {
      implicitly[EntityEncoder[Stream[Task, String]]].headers.get(`Transfer-Encoding`) must beLike {
        case Some(coding) => coding.hasChunked must beTrue
      }
    }

    "render bytebuffer processes with chunked transfer encoding" in {
      implicitly[EntityEncoder[Stream[Task, ByteBuffer]]].headers.get(`Transfer-Encoding`) must beLike {
        case Some(coding) => coding.hasChunked must beTrue
      }
    }

    "render processes with chunked transfer encoding without wiping out other encodings" in {
      trait Foo
      implicit val FooEncoder: EntityEncoder[Foo] =
        EntityEncoder.encodeBy(`Transfer-Encoding`(TransferCoding.gzip)) { _ => Task.now(Entity.empty) }
      implicitly[EntityEncoder[Stream[Task, Foo]]].headers.get(`Transfer-Encoding`) must beLike {
        case Some(coding) => coding must_== `Transfer-Encoding`(TransferCoding.gzip, TransferCoding.chunked)
      }
    }

    "render processes with chunked transfer encoding without duplicating chunked transfer encoding" in {
      trait Foo
      implicit val FooEncoder: EntityEncoder[Foo] =
        EntityEncoder.encodeBy(`Transfer-Encoding`(TransferCoding.chunked)) { _ => Task.now(Entity.empty) }
      implicitly[EntityEncoder[Stream[Task, Foo]]].headers.get(`Transfer-Encoding`) must beLike {
        case Some(coding) => coding must_== `Transfer-Encoding`(TransferCoding.chunked)
      }
    }

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
      val inputStream = Eval.always(new ByteArrayInputStream(("input stream").getBytes(StandardCharsets.UTF_8)))
      writeToString(inputStream) must_== "input stream"
    }

    /* TODO fs2 port
    "render readers" in {
      val reader = new StringReader("string reader")
      writeToString(reader) must_== "string reader"
    }
     */

    "give the content type" in {
      EntityEncoder[String].contentType must_== Some(`Content-Type`(MediaType.`text/plain`, Charset.`UTF-8`))
      EntityEncoder[Array[Byte]].contentType must_== Some(`Content-Type`(MediaType.`application/octet-stream`))
      EntityEncoder[ByteBuffer].contentType must_== Some(`Content-Type`(MediaType.`application/octet-stream`))
    }

    "work with local defined EntityEncoders" in {
      sealed case class ModelA(name: String, color: Int)
      sealed case class ModelB(name: String, id: Long)

      implicit val w1: EntityEncoder[ModelA] = EntityEncoder.simple[ModelA]()(_ => Chunk.bytes("A".getBytes))
      implicit val w2: EntityEncoder[ModelB] = EntityEncoder.simple[ModelB]()(_ => Chunk.bytes("B".getBytes))

      EntityEncoder[ModelA] must_== w1
      EntityEncoder[ModelB] must_== w2
    }
  }
}

