import migration.data.{ Document => _, _ }
import migration.dsl._
import java.io.IOException
import org.mongodb.scala._
import scalaz.zio.{ IO, App }
import scalaz.zio.console._

package object migration {
  val lineFeed = putStrLn("")
  val indent = putStr("  ")
}