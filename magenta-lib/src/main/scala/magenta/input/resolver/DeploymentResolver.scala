package magenta.input.resolver

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList => NEL, Validated, ValidatedNel}
import cats.syntax.cartesian._
import cats.instances.all._
import magenta.input.{ConfigError, Deployment, DeploymentOrTemplate, RiffRaffDeployConfig}

object DeploymentResolver {
  def resolve(config: RiffRaffDeployConfig): ValidatedNel[ConfigError, List[Deployment]] = {
    config.deployments.map { case (label, rawDeployment) =>
      applyTemplates(label, rawDeployment, config.templates).andThen { templated =>
        resolveDeployment(label, templated, config.stacks, config.regions)
      }.andThen { deployment =>
        validateDependencies(label, deployment, config.deployments)
      }.map(List(_))
    }.reduceLeft(_ combine _)
  }

  /**
    * Validates and resolves a templated deployment by merging its
    * deployment attributes with any globally defined properties.
    */
  private[input] def resolveDeployment(label: String, templated: DeploymentOrTemplate, globalStacks: Option[List[String]], globalRegions: Option[List[String]]): ValidatedNel[ConfigError, Deployment] = {
    (Validated.fromOption(templated.`type`, ConfigError.nel(label, "No type field provided")) |@|
      Validated.fromOption(templated.stacks.orElse(globalStacks), ConfigError.nel(label, "No stacks provided")) |@|
      Validated.fromOption(templated.regions.orElse(globalRegions), ConfigError.nel(label, "No regions provided"))) map { (deploymentType, stacks, regions) =>
      Deployment(
        name = label,
        `type` = deploymentType,
        stacks = NEL.fromListUnsafe(stacks),
        regions = NEL.fromListUnsafe(regions),
        actions = templated.actions,
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
  private[input] def applyTemplates(templateName: String, template: DeploymentOrTemplate, templates: Option[Map[String, DeploymentOrTemplate]]): ValidatedNel[ConfigError, DeploymentOrTemplate] = {
    template.template match {
      case None =>
        Valid(template)
      case Some(parentTemplateName) =>
        Validated.fromOption(templates.flatMap(_.get(parentTemplateName)),
          ConfigError.nel(templateName, s"Template with name $parentTemplateName does not exist")).andThen { parentTemplate =>
          applyTemplates(parentTemplateName, parentTemplate, templates)
        }.map { resolvedParent =>
          DeploymentOrTemplate(
            `type` = template.`type`.orElse(resolvedParent.`type`),
            template = None,
            stacks = template.stacks.orElse(resolvedParent.stacks),
            regions = template.regions.orElse(resolvedParent.regions),
            actions = template.actions.orElse(resolvedParent.actions),
            app = template.app.orElse(resolvedParent.app),
            contentDirectory = template.contentDirectory.orElse(resolvedParent.contentDirectory),
            dependencies = template.dependencies.orElse(resolvedParent.dependencies),
            parameters = Some(resolvedParent.parameters.getOrElse(Map.empty) ++ template.parameters.getOrElse(Map.empty))
          )
        }
    }
  }

  /**
    * Ensures that when deployments have named dependencies, deployments with those names exists.
    */
  private[input] def validateDependencies(label: String, deployment: Deployment, allDeployments: List[(String, DeploymentOrTemplate)]): ValidatedNel[ConfigError, Deployment] = {
    val allDeploymentNames = allDeployments.map { case (name, _) => name }
    deployment.dependencies.filterNot(allDeploymentNames.contains) match {
      case Nil =>
        Valid(deployment)
      case missingDependencies =>
        Invalid(ConfigError.nel(label, missingDependencies.mkString(s"Missing deployment dependencies ", ", ", "")))
    }
  }
}
