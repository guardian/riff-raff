package com.gu.deploy.tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec


class CommandLineTest extends FlatSpec with ShouldMatchers {


  "CommandLine" should "return sensible description for simple commands" in {
    CommandLine(List("ls", "-l")).quoted should be ("ls -l")
  }

  it should "return quoted description for commands with string params with spaces" in {
    CommandLine(List("echo", "this needs to be quoted")).quoted should
      be ("echo \"this needs to be quoted\"")
  }

}