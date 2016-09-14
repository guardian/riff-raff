package magenta.input

import play.api.libs.json.JsValue

case class Deployment(
  name: String,
  `type`: String,
  stacks: List[String],
  regions: List[String],
  dependencies: List[String],
  app: String,
  contentDirectory: String,
  parameters: Map[String, JsValue]
)

object DeploymentResolver {
  val DEFAULT_REGIONS = List("eu-west-1")

  def resolveDeployment(name: String, deployment: DeploymentOrTemplate, yaml: RiffRaffYaml): Either[List[String], DeploymentOrTemplate] = {
    val missingDeps = yaml.missingDependencies(deployment.dependencies.getOrElse(Nil))
    if (missingDeps.nonEmpty)
      Left(missingDeps.map(dep => s"Missing dependency $dep in $name"))
    else {
      deployment match {
        case deployment @ DeploymentOrTemplate(Some(deploymentType), None, stacks, regions, _, _, _, _) =>
          // terminating case - apply any default stacks and regions
          val deploymentWithGlobalDefaults = deployment.copy(stacks = stacks.orElse(yaml.stacks), regions = regions.orElse(yaml.regions))
          Right(deploymentWithGlobalDefaults)

        case template @ DeploymentOrTemplate(None, Some(templateName), stacks, regions, app, contentDirectory, dependencies, parameters) =>
          // template case - recursively resolve
          for {
            template <- yaml.templates.flatMap(_.get(templateName))
              .toRight(List(s"Template with name $templateName (specified in $name) does not exist")).right
            resolved <- resolveDeployment(templateName, template, yaml).right
          } yield {
            DeploymentOrTemplate(
              resolved.`type`,
              None,
              stacks.orElse(resolved.stacks),
              regions.orElse(resolved.regions),
              app.orElse(resolved.app),
              contentDirectory.orElse(resolved.contentDirectory),
              dependencies.orElse(resolved.dependencies),
              Some(resolved.parameters.getOrElse(Map.empty) ++ parameters.getOrElse(Map.empty))
            )
          }
      }
    }
  }

  def resolve(yaml: RiffRaffYaml): List[Either[List[String], Deployment]] = {
    yaml.deployments.map { case (name, deployment) =>
      resolveDeployment(name, deployment, yaml).right.map { resolved =>
        Deployment(
          name = name,
          `type` = resolved.`type`.get,
          stacks = resolved.stacks.get,
          regions = resolved.regions.getOrElse(DEFAULT_REGIONS),
          dependencies = resolved.dependencies.getOrElse(Nil),
          app = resolved.app.getOrElse(name),
          contentDirectory = resolved.contentDirectory.getOrElse(name),
          parameters = resolved.parameters.getOrElse(Map.empty)
        )
      }
    }.toList
  }
}
