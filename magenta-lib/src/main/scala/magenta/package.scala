package magenta

import java.io.Closeable

object `package` {
  def transpose[A](xs: Seq[Seq[A]]): Seq[Seq[A]] = xs.filter(_.nonEmpty) match {
    case Nil => Nil
    case ys: Seq[Seq[A]] => ys.map{ _.head } +: transpose(ys.map{ _.tail })
  }

  implicit class Seq2TransposeBy[A](seq: Seq[A]) {
    def transposeBy[K](f: A => K)(implicit ord:Ordering[K]): Seq[A] = {
      val listOfGroups = seq.groupBy(f).toList.sortBy(_._1).map(_._2)
      transpose(listOfGroups).fold(Nil)(_ ++ _)
    }
  }

  def withResource[C <: Closeable, T](resource: C)(f: C => T): T = {
    try {
      f(resource)
    } finally {
      resource.close()
    }
  }
}