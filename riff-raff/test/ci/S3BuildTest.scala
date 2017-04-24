package ci

import org.joda.time.DateTime
import org.scalatest.{EitherValues, FunSuite, Matchers}

class S3BuildTest extends FunSuite with Matchers with EitherValues {

  test("can parse build.json") {
    val json =
      """
        |{
        |  "projectName": "foo",
        |  "buildNumber": "42",
        |  "startTime": "2017-03-14T17:57:08.000Z",
        |  "vcsURL": "git@github.com:guardian/riff-raff.git",
        |  "branch": "master",
        |  "revision": "f29427661d227eaf3e6b89c75e76b99484d551c4"
        |}
      """.stripMargin

    S3Build.parse(json).right.value shouldBe (
      S3Build(
        42,
        "foo",
        "foo",
        "master",
        "42",
        new DateTime(2017, 3, 14, 17, 57, 8),
        "f29427661d227eaf3e6b89c75e76b99484d551c4",
        "git@github.com:guardian/riff-raff.git"
      )
    )
  }

  test("parsing should not barf if buildNumber is not a number") {
    val json =
      """
        |{
        |  "projectName": "foo",
        |  "buildNumber": "unknown",
        |  "startTime": "2017-03-14T17:57:08.000Z",
        |  "vcsURL": "git@github.com:guardian/riff-raff.git",
        |  "branch": "master",
        |  "revision": "f29427661d227eaf3e6b89c75e76b99484d551c4"
        |}
      """.stripMargin

    assert(S3Build.parse(json).isLeft)
  }
}
