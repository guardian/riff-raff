package magenta.schema

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Entry point for the sbt generateSchema task. Writes the JSON Schema to the
  * path given as the first argument.
  */
object GenerateJsonSchemaMain {

  private def parseArgs(args: Array[String]): Either[String, Path] =
    args match {
      case Array(path) => Right(Paths.get(path))
      case Array() => Right(Paths.get("contrib", "riff-raff-yaml-schema.json"))
      case _       => Left("Usage: GenerateJsonSchemaMain [output-path]")
    }

  private def writeFile(path: Path, content: String): Either[String, Unit] =
    Try(Files.write(path, content.getBytes("UTF-8"))).toEither.left
      .map(_.getMessage)
      .map(_ => ())

  def main(args: Array[String]): Unit = {
    val result = for {
      targetPath <- parseArgs(args)
      schema = GenerateJsonSchema.generate(GenerateJsonSchema.deploymentTypes)
      _ <- writeFile(targetPath, schema)
    } yield s"Wrote ${schema.length} characters to ${targetPath.toAbsolutePath}"

    result match {
      case Right(message) => println(message)
      case Left(error)    =>
        System.err.println(s"Error: $error")
        sys.exit(1)
    }
  }
}
