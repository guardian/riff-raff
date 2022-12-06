package ci

import cats.syntax.either._
import conf.Config
import controllers.Logging
import lifecycle.Lifecycle
import magenta.Build
import magenta.artifact._
import magenta.deployment_type.DeploymentType
import magenta.graph.Graph
import magenta.input.resolver.{PartiallyResolvedDeployment, Resolver}
import magenta.input.{All, ConfigErrors, Deployment}
import persistence.TargetDynamoRepository

case class Target(region: String, stack: String, app: String)

object TargetResolver {
  def extractTargets(graph: Graph[Deployment]): Set[Target] = {
    graph.nodes.values.flatMap { deployment =>
      for {
        region <- deployment.regions.toList
        stack <- deployment.stacks.toList
      } yield Target(region, stack, deployment.app)
    }
  }
  def extractTargets(
      deployConfig: List[PartiallyResolvedDeployment]
  ): Set[Target] = {
    deployConfig.flatMap { deployment =>
      {
        for {
          region <- deployment.regions.toList
          stack <- deployment.stacks.toList
        } yield Target(region, stack, deployment.app)
      }
    }.toSet
  }
}

class TargetResolver(
    config: Config,
    ciBuildPoller: CIBuildPoller,
    deploymentTypes: Seq[DeploymentType],
    targetDynamoRepository: TargetDynamoRepository
) extends Lifecycle
    with Logging {

  val poller = ciBuildPoller.newBuilds.subscribe { build =>
    val result = for {
      yaml <- fetchYaml(build.toMagentaBuild)
      deployGraph <- Resolver
        .resolveDeploymentGraph(yaml, deploymentTypes, All)
        .toEither
      targets = TargetResolver.extractTargets(deployGraph)
    } yield {
      targets.map { t =>
        Either.catchNonFatal(
          t -> targetDynamoRepository.set(t, build.jobName, build.startTime)
        )
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
          case UnknownS3Error(exception) =>
            s"Unknown S3 error" -> Some(exception)
          case ConfigErrors(errors) =>
            s"Configuration errors: ${errors.toList.mkString("; ")}" -> None
          case _ => s"Unknown error" -> None
        }
        message match {
          case (msg, Some(t)) =>
            log.warn(s"Error resolving target for $build: $msg", t)
          case (msg, None) =>
            log.warn(s"Error resolving target for $build: $msg")
        }
    }
  }

  def fetchYaml(build: Build): Either[S3Error, String] = {
    val artifact = S3YamlArtifact(build, config.artifact.aws.bucketName)
    val deployObjectPath = artifact.deployObject
    S3Location.fetchContentAsString(deployObjectPath)(
      config.artifact.aws.client
    )
  }

  override def init() = {}

  override def shutdown() = {
    poller.unsubscribe()
  }
}
