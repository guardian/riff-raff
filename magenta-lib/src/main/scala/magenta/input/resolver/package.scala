package magenta.input

import cats.data.Validated

package object resolver {
  implicit class RichValidated[E, A](validated: Validated[E, A]) {
    def flatMap[EE >: E, B](f: A => Validated[EE, B]): Validated[EE, B] =
      validated.andThen(f)
  }
}
