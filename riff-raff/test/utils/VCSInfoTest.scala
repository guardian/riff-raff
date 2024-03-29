package utils

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.net.URI

class VCSInfoTest extends AnyFunSuite with Matchers {
  test("extracts details from Github HTTPS URL") {
    val info = VCSInfo(
      "https://github.com/guardian/contributions-frontend",
      "98a1cffadbe564d570b15c2113c07a28cbe835ee"
    )
    info.get.baseUrl shouldBe new URI(
      "https://github.com/guardian/contributions-frontend"
    )
  }

  test("extracts details from Github git URL") {
    val info = VCSInfo(
      "git@github.com:guardian/contributions-frontend.git",
      "98a1cffadbe564d570b15c2113c07a28cbe835ee"
    )
    info.get.baseUrl shouldBe new URI(
      "https://github.com/guardian/contributions-frontend"
    )
  }

  test("extracts details from Github HTTPS URL with .git extension") {
    val info = VCSInfo(
      "https://github.com/guardian/contributions-frontend.git",
      "98a1cffadbe564d570b15c2113c07a28cbe835ee"
    )
    info.get.baseUrl shouldBe new URI(
      "https://github.com/guardian/contributions-frontend"
    )
  }

  test("normalises repository string") {
    val cases = Map(
      "git@github.com:guardian/actions-riff-raff.git" -> Some(
        "guardian/actions-riff-raff"
      ),
      "https://github.com/guardian/actions-riff-raff.git" -> Some(
        "guardian/actions-riff-raff"
      ),
      "git://github.com/guardian/actions-riff-raff.git" -> Some(
        "guardian/actions-riff-raff"
      ),
      "http://example.com/actions-riff-raff.git" -> None
    )

    cases.foreach {
      case (url, want) => {
        VCSInfo.normalise(url) shouldBe want
      }
    }
  }
}
