package org.http4s
package headers

import java.time.{Instant, ZoneId, ZonedDateTime}

class DateSpec extends HeaderLaws {
  checkAll("Date", headerLaws(Date))

  val gmtDate = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneId.of("GMT"))

  "render" should {
    "format GMT date according to RFC 1123" in {
      Date(Instant.from(gmtDate)).renderString must_== "Date: Sun, 06 Nov 1994 08:49:37 GMT"
    }
    "format UTC date according to RFC 1123" in {
      val utcDate = ZonedDateTime.of(1994, 11, 6, 8, 49, 37, 0, ZoneId.of("UTC"))
      Date(Instant.from(utcDate)).renderString must_== "Date: Sun, 06 Nov 1994 08:49:37 GMT"
    }
  }
}
