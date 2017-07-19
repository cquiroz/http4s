/* Derived from https://raw.githubusercontent.com/etorreborre/specs2/c0cbfc71390b644db1a5deeedc099f74a237ebde/matcher-extra/src/main/scala-scalaz-7.0.x/org/specs2/matcher/TaskMatchers.scala
 * License: https://raw.githubusercontent.com/etorreborre/specs2/master/LICENSE.txt
 */
package org.http4s.testing

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

import fs2.Task
import org.specs2._
import org.specs2.matcher._
import org.specs2.matcher.ValueChecks._

/**
 * Matchers for scalaz.concurrent.Task
 */
trait TaskMatchers extends PlatformTaskMatchers {
  def returnOk[T]: TaskMatcher[T] =
    attemptRun(ValueCheck.alwaysOk, None)

  def returnValue[T](check: ValueCheck[T]): TaskMatcher[T] =
    attemptRun(check, None)

  def returnBefore[T](duration: FiniteDuration): TaskMatcher[T] =
    attemptRun(ValueCheck.alwaysOk, Some(duration))

  private def attemptRun[T](check: ValueCheck[T], duration: Option[FiniteDuration]): TaskMatcher[T] =
    TaskMatcher(check, duration)

}

object TaskMatchers extends TaskMatchers
