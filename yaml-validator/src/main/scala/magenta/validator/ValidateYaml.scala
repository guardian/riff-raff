package magenta.validator

import cats.data.Validated.{Invalid, Valid}
import magenta.deployment_type._
import magenta.input.All
import magenta.input.resolver.Resolver

import scala.io.Source
import scala.util.{Failure, Success, Try}

/** A standalone command-line tool to validate riff-raff.yaml configuration
  * files.
  *
  * Performs the same validation as
  * https://riffraff.gutools.co.uk/configuration/validation without requiring a
  * running Riff-Raff instance.
  *
  * Usage: validate-yaml <path-to-riff-raff.yaml>
  *
  * Exit codes: 0 - validation passed 1 - validation failed (errors in the YAML)
  * 2 - usage error (wrong arguments, file not found, etc.)
  */
object ValidateYaml {

  /** A no-op BuildTags implementation for validation purposes. During
    * validation we don't have access to build information, so we provide empty
    * tags. This doesn't affect structural validation of the YAML.
    */
  private object NoopBuildTags extends BuildTags {
    def get(projectName: String, buildId: String): Map[String, String] =
      Map.empty
  }

  val deploymentTypes: Seq[DeploymentType] = Seq(
    S3,
    AutoScaling,
    Fastly,
    FastlyCompute,
    new CloudFormation(NoopBuildTags),
    Lambda,
    LambdaLayer,
    AmiCloudFormationParameter,
    SelfDeploy
  )

  def main(args: Array[String]): Unit = {
    args.toList match {
      case filePath :: Nil =>
        val exitCode = validate(filePath)
        sys.exit(exitCode)
      case _ =>
        Console.err.println("Usage: validate-yaml <path-to-riff-raff.yaml>")
        sys.exit(2)
    }
  }

  /** Validate a riff-raff.yaml file at the given path.
    *
    * @return
    *   0 if valid, 1 if invalid, 2 if there was a usage/IO error
    */
  def validate(filePath: String): Int = {
    readFile(filePath) match {
      case Failure(exception) =>
        Console.err.println(s"Error reading file: ${exception.getMessage}")
        2
      case Success(yaml) =>
        validateYaml(yaml)
    }
  }

  /** Validate YAML content directly.
    *
    * @return
    *   0 if valid, 1 if invalid
    */
  def validateYaml(yaml: String): Int =
    Resolver.resolveDeploymentGraph(yaml, deploymentTypes, All) match {
      case Valid(deployments) =>
        println("✅ Validation passed\n")
        deployments.toList.foreach(printDeployment)
        0

      case Invalid(errors) =>
        Console.err.println("❌ Validation failed\n")
        errors.errors.toList.groupBy(_.context).foreach {
          case (context, contextErrors) =>
            Console.err.println(s"  Error in $context:")
            contextErrors
              .foreach(e => Console.err.println(s"    - ${e.message}"))
            Console.err.println()
        }
        1
    }

  private def printDeployment(
      deployment: magenta.input.Deployment
  ): Unit = {
    val fields = Seq(
      "Type" -> deployment.`type`,
      "Actions" -> deployment.actions.toList.mkString(", "),
      "Stacks" -> deployment.stacks.toList.mkString(", "),
      "Regions" -> deployment.regions.toList.mkString(", "),
      "App" -> deployment.app,
      "Content directory" -> deployment.contentDirectory
    )
    println(s"  Deployment: ${deployment.name}")
    fields.foreach { case (label, value) =>
      println(s"    ${label.padTo(18, ' ')}$value")
    }
    if (deployment.parameters.nonEmpty) {
      println("    Parameters:")
      deployment.parameters.foreach { case (name, value) =>
        println(s"      $name: $value")
      }
    }
    if (deployment.dependencies.nonEmpty)
      println(
        s"    Dependencies:      ${deployment.dependencies.mkString(", ")}"
      )
    println()
  }

  private def readFile(filePath: String): Try[String] = Try {
    val source = Source.fromFile(filePath, "UTF-8")
    try source.mkString
    finally source.close()
  }
}
