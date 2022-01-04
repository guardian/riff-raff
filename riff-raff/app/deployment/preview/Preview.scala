package deployment.preview

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.kernel.Semigroup
import cats.syntax.semigroup._
import magenta.`package`._
import magenta.artifact.S3YamlArtifact
import magenta.deployment_type.DeploymentType
import magenta.graph.{DeploymentTasks, Graph}
import magenta.input._
import magenta.input.resolver._
import magenta.{DeployParameters, DeploymentResources, Loggable}

object Preview extends Loggable {
  def apply(artifact: S3YamlArtifact, yamlConfig: String, parameters: DeployParameters,
    resources: DeploymentResources, deploymentTypes: Seq[DeploymentType]): Preview = {

    val validatedGraph = for {
      deploymentGraph <- Resolver.resolveDeploymentGraph(yamlConfig, deploymentTypes, parameters.selector)
      flattenedGraph = DeploymentGraphActionFlattening.flattenActions(deploymentGraph)
      previewGraph <- sequenceGraph {
        flattenedGraph.map { deployment =>
          val key = DeploymentKey(deployment)
          TaskResolver.resolve(deployment, resources, parameters, deploymentTypes, artifact).map(key -> _)
        }
      }
    } yield previewGraph
    Preview(validatedGraph, parameters)
  }

  private[preview] def sequenceGraph[A, E](graph: Graph[Validated[E, A]])(implicit E: Semigroup[E]): Validated[E, Graph[A]] = {
    val anyErrors = graph.toList.collect{case Invalid(errors) => errors}
    if (anyErrors.nonEmpty) {
      Invalid(anyErrors.reduce(_ |+| _))
    } else {
      Valid(graph.map{
        case Valid(node) => node
        case Invalid(_) => `wtf?`
      })
    }
  }
}

case class Preview(graph: Validated[ConfigErrors, Graph[(DeploymentKey, DeploymentTasks)]], parameters: DeployParameters)
