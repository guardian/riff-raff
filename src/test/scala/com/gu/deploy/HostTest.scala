package com.gu.deploy

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class HostTest extends FlatSpec with ShouldMatchers {
  "host" should "calculate colo correctly" in {
    Host("a.b.c").colo should be (Some("b"))
    Host("a").colo should be (None)
  }
}