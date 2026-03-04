package magenta.schema

import magenta.deployment_type.AllTypes

import java.nio.file.{Files, Paths}

/** Entry point for the sbt generateSchema task. Writes the JSON Schema to the
  * path given as the first argument.
  */
object GenerateJsonSchemaMain {
  def main(args: Array[String]): Unit = {
    val targetPath = args match {
      case Array(path) => Paths.get(path)
      case Array() =>
        Paths.get("contrib", "riff-raff-yaml-schema.json")
      case _ =>
        throw new IllegalArgumentException(
          "Usage: GenerateJsonSchemaMain [output-path]"
        )
    }

    val schema =
      GenerateJsonSchema.generate(AllTypes.allDeploymentTypesForSchema)

    Files.write(targetPath, schema.getBytes("UTF-8"))
    println(
      s"Wrote ${schema.length} characters to ${targetPath.toAbsolutePath}"
    )
  }
}
