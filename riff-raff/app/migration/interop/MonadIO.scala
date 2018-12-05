package migration
package interop

import scala.language.higherKinds
import scalaz.FreeT
import scalaz.zio.IO
import scalaz.zio.interop.scalaz72._

trait MonadIO[F[_, _]] {
  def lift[E, A](io: IO[E, A]): F[E, A]
}

object MonadIO {
  implicit def freeT[S[_]] = new MonadIO[λ[(E, A) => FreeT[S, IO[E, ?], A]]] {
    def lift[E, A](io: IO[E, A]) = FreeT.liftM(io)
  }

  implicit class Ops[E, A](val io: IO[E, A]) extends AnyVal {
    def lift[F[_, _]](implicit F: MonadIO[F]): F[E, A] = F.lift(io)
  }
}