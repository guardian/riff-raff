package magenta.fixtures

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import org.scalatest.exceptions.TestFailedException

/** This trait is inspired strongly by [[org.scalatest.EitherValues]] as a way
  * of simply asserting that you expect either a Valid or Invalid value from a
  * Validated type.
  *
  * Two functions are pimped onto Validated for the purposes of testing and if
  * the value is not of the expected type then the test will fail detailing what
  * the unexpected value was.
  */
trait ValidatedValues {

  import scala.language.implicitConversions

  implicit class ScalaTestValidated[E, A](validated: Validated[E, A]) {
    def valid: A = {
      validated match {
        case Valid(value)    => value
        case Invalid(errors) =>
          throw new TestFailedException(
            s"$validated was not of type Valid",
            1
          )
      }
    }
    def invalid: E = {
      validated match {
        case Valid(value) =>
          throw new TestFailedException(
            s"$validated was not of type Invalid",
            1
          )
        case Invalid(errors) => errors
      }
    }
  }

}

object ValidatedValues extends ValidatedValues
