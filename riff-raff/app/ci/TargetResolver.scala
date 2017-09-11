package ci

import cats.data.Validated
import conf.Configuration
import controllers.Logging
import lifecycle.Lifecycle
import magenta.Build
import magenta.artifact._
import magenta.deployment_type.DeploymentType
import magenta.graph.Graph
import magenta.input.{All, Deployment}
import magenta.input.resolver.Resolver
import persistence.TargetDynamoRepository

case class Target(region: String, stack: String, app: String)

class TargetResolver(ciBuildPoller: CIBuildPoller, deploymentTypes: Seq[DeploymentType]) extends Lifecycle with Logging {
  val poller = ciBuildPoller.newBuilds.subscribe { build =>
    for {
      yaml <- Validated.fromEither(fetchYaml(build.jobName, build.jobId))
      deployGraph <- Resolver.resolveDeploymentGraph(yaml._2, deploymentTypes, All)
      targets = extractTargets(deployGraph)
    } {
      targets.foreach{ t =>
        TargetDynamoRepository.set(t, build.jobName)
      }
    }
  }

  def fetchYaml(name: String, id: String): Either[S3Error, (S3Path, String)] = {
    val build = Build(name, id)
    val artifact = S3YamlArtifact(build, Configuration.artifact.aws.bucketName)
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
