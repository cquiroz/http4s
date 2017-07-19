package org.http4s

import java.io.{InputStream, File, FileInputStream}
import java.nio.file.Path
import cats.Eval
import fs2.Task
import fs2.io.readInputStream

trait FileEntityEncoder extends EntityEncoderInstances {

  // TODO parameterize chunk size
  // TODO if Header moves to Entity, can add a Content-Disposition with the filename
  implicit val fileEncoder: EntityEncoder[File] =
    inputStreamEncoder.contramap(file => Eval.always(new FileInputStream(file)))

  // TODO parameterize chunk size
  // TODO if Header moves to Entity, can add a Content-Disposition with the filename
  implicit val filePathEncoder: EntityEncoder[Path] =
    fileEncoder.contramap(_.toFile)

  // TODO parameterize chunk size
  implicit def inputStreamEncoder[A <: InputStream]: EntityEncoder[Eval[A]] =
    sourceEncoder[Byte].contramap { in: Eval[A] =>
      readInputStream[Task](Task.delay(in.value), DefaultChunkSize)
    }

}
