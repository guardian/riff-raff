package utils

import java.net.URI

import org.scalatest.{FunSuite, Matchers}


class VCSInfoTest extends FunSuite with Matchers {
  test("extracts details from Github HTTPS URL") {
    val info = VCSInfo("https://github.com/guardian/contributions-frontend", "98a1cffadbe564d570b15c2113c07a28cbe835ee")
    info.get.baseUrl shouldBe new URI("https://github.com/guardian/contributions-frontend")
  }

  test("extracts details from Github git URL") {
    val info = VCSInfo("git@github.com:guardian/contributions-frontend.git", "98a1cffadbe564d570b15c2113c07a28cbe835ee")
    info.get.baseUrl shouldBe new URI("https://github.com/guardian/contributions-frontend")
  }
}
