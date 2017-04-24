package magenta.input.resolver

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Validated, NonEmptyList => NEL}
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.instances.list._
import magenta.input._
import play.api.libs.json.JsValue

object DeploymentResolver {
  def resolve(config: RiffRaffDeployConfig): Validated[ConfigErrors, List[PartiallyResolvedDeployment]] = {
    config.deployments.traverseU[Validated[ConfigErrors, PartiallyResolvedDeployment]] {
      case (label, rawDeployment) =>
        for {
          templated <- applyTemplates(label, rawDeployment, config.templates)
          deployment <- resolveDeployment(label, templated, config.stacks, config.regions)
          validatedDeployment <- validateDependencies(label, deployment, config.deployments)
        } yield validatedDeployment
    }
  }

  /**
    * Validates and resolves a templated deployment by merging its
    * deployment attributes with any globally defined properties.
    */
  private[input] def resolveDeployment(
      label: String,
      templated: DeploymentOrTemplate,
      globalStacks: Option[List[String]],
      globalRegions: Option[List[String]]): Validated[ConfigErrors, PartiallyResolvedDeployment] = {
    (Validated.fromOption(templated.`type`, ConfigErrors(label, "No type field provided")) |@|
      Validated.fromOption(templated.stacks.orElse(globalStacks).flatMap(NEL.fromList),
                           ConfigErrors(label, "No stacks provided")) |@|
      Validated.fromOption(templated.regions.orElse(globalRegions).flatMap(NEL.fromList),
                           ConfigErrors(label, "No regions provided"))) map { (deploymentType, stacks, regions) =>
      PartiallyResolvedDeployment(
        name = label,
        `type` = deploymentType,
        stacks = stacks,
        regions = regions,
        actions = templated.actions.flatMap(NEL.fromList),
        app = templated.app.getOrElse(label),
        contentDirectory = templated.contentDirectory.getOrElse(label),
        dependencies = templated.dependencies.getOrElse(Nil),
        parameters = templated.parameters.getOrElse(Map.empty)
      )
    }
  }

  /**
    * Recursively apply named templates by merging the provided
    * deployment template with named parent templates.
    */
  private[input] def applyTemplates(
      templateName: String,
      template: DeploymentOrTemplate,
      templates: Option[Map[String, DeploymentOrTemplate]]): Validated[ConfigErrors, DeploymentOrTemplate] = {

    template.template match {
      case None =>
        Valid(template)
      case Some(parentTemplateName) =>
        for {
          parentTemplate <- {
            Validated.fromOption(templates.flatMap(_.get(parentTemplateName)),
                                 ConfigErrors(templateName, s"Template with name $parentTemplateName does not exist"))
          }
          resolvedParent <- applyTemplates(parentTemplateName, parentTemplate, templates)
        } yield {
          DeploymentOrTemplate(
            `type` = template.`type`.orElse(resolvedParent.`type`),
            template = None,
            stacks = template.stacks.orElse(resolvedParent.stacks),
            regions = template.regions.orElse(resolvedParent.regions),
            actions = template.actions.orElse(resolvedParent.actions),
            app = template.app.orElse(resolvedParent.app),
            contentDirectory = template.contentDirectory.orElse(resolvedParent.contentDirectory),
            dependencies = template.dependencies.orElse(resolvedParent.dependencies),
            parameters =
              Some(resolvedParent.parameters.getOrElse(Map.empty) ++ template.parameters.getOrElse(Map.empty))
          )
        }
    }
  }

  /**
    * Ensures that when deployments have named dependencies, deployments with those names exists.
    */
  private[input] def validateDependencies(
      label: String,
      deployment: PartiallyResolvedDeployment,
      allDeployments: List[(String, DeploymentOrTemplate)]): Validated[ConfigErrors, PartiallyResolvedDeployment] = {
    val allDeploymentNames = allDeployments.map { case (name, _) => name }
    deployment.dependencies.filterNot(allDeploymentNames.contains) match {
      case Nil =>
        Valid(deployment)
      case missingDependencies =>
        Invalid(ConfigErrors(label, missingDependencies.mkString(s"Missing deployment dependencies ", ", ", "")))
    }
  }
}

private[resolver] case class PartiallyResolvedDeployment(
    name: String,
    `type`: String,
    stacks: NEL[String],
    regions: NEL[String],
    actions: Option[NEL[String]],
    app: String,
    contentDirectory: String,
    dependencies: List[String],
    parameters: Map[String, JsValue]
)
