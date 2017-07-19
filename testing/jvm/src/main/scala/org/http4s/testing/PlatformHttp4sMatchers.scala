package org.http4s
package testing

import cats.data._
import fs2._
import org.http4s.headers._
import org.specs2.matcher._

/** This might be useful in a testkit spinoff.  Let's see what they do for us. */
// TODO these akas might be all wrong.
trait PlatformHttp4sMatchers extends Matchers with TaskMatchers {
  def haveStatus(expected: Status): Matcher[Response] =
    be_===(expected) ^^ { r: Response =>
      r.status aka "the response status"
    }

  def returnStatus(s: Status): Matcher[Task[Response]] =
    haveStatus(s) ^^ { r: Task[Response] =>
      r.unsafeRun aka "the returned"
    }

  def returnRight[A, B](m: ValueCheck[B]): Matcher[EitherT[Task, A, B]] =
    beRight(m) ^^ { et: EitherT[Task, A, B] =>
      et.value.unsafeRun aka "the either task"
    }

  def returnLeft[A, B](m: ValueCheck[A]): Matcher[EitherT[Task, A, B]] =
    beLeft(m) ^^ { et: EitherT[Task, A, B] =>
      et.value.unsafeRun aka "the either task"
    }
}
