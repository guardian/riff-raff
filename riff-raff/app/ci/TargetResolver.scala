package ci

import cats.syntax.either._
import conf.Configuration
import controllers.Logging
import lifecycle.Lifecycle
import magenta.artifact._
import magenta.deployment_type.DeploymentType
import magenta.graph.Graph
import magenta.input.{All, ConfigErrors, Deployment}
import magenta.input.resolver.Resolver
import persistence.TargetDynamoRepository

case class Target(region: String, stack: String, app: String)

class TargetResolver(ciBuildPoller: CIBuildPoller, deploymentTypes: Seq[DeploymentType]) extends Lifecycle with Logging {
  val poller = ciBuildPoller.newBuilds.subscribe { build =>
    val result = for {
      yaml <- fetchYaml(build)
      deployGraph <- Resolver.resolveDeploymentGraph(yaml._2, deploymentTypes, All).toEither
      targets = extractTargets(deployGraph)
    } yield {
      targets.map{ t =>
        Either.catchNonFatal(t -> TargetDynamoRepository.set(t, build.jobName, build.startTime))
      }
    }
    result match {
      case Right(putResults) =>
        putResults.foreach {
          case Right((target, _)) => log.debug(s"Persisted $target for $build")
          case Left(t) => log.warn(s"Error persisting target for $build", t)
        }
      case Left(error) =>
        val message: (String, Option[Throwable]) = error match {
          case EmptyS3Location(location) => s"Empty location: $location" -> None
          case UnknownS3Error(exception) => s"Unknown S3 error" -> Some(exception)
          case ConfigErrors(errors) => s"Configuration errors: ${errors.toList.mkString("; ")}" -> None
          case _ => s"Unknown error" -> None
        }
        message match {
          case (msg, Some(t)) => log.warn(s"Error resolving target for $build: $msg", t)
          case (msg, None) => log.warn(s"Error resolving target for $build: $msg")
        }
    }
  }

  def fetchYaml(build: CIBuild): Either[S3Error, (S3Path, String)] = {
    val artifact = S3YamlArtifact(build.toMagentaBuild, Configuration.artifact.aws.bucketName)
    val deployObjectPath = artifact.deployObject
    val deployObjectContent = S3Location.fetchContentAsString(deployObjectPath)(Configuration.artifact.aws.client)
    deployObjectContent.map(deployObjectPath -> _)
  }

  def extractTargets(graph: Graph[Deployment]): Set[Target] = {
    graph.nodes.values.flatMap { deployment =>
      for {
        region <- deployment.regions.toList
        stack <- deployment.stacks.toList
      } yield Target(region, stack, deployment.app)
    }
  }

  override def init() = {}

  override def shutdown() = {
    poller.unsubscribe()
  }
}
