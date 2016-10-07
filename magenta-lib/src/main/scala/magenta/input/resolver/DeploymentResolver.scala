package magenta.input.resolver

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList => NEL, Validated, ValidatedNel}
import cats.syntax.cartesian._
import cats.syntax.traverse._
import cats.instances.list._
import magenta.input.{ConfigError, Deployment, DeploymentOrTemplate, RiffRaffDeployConfig}

object DeploymentResolver {
  def resolve(config: RiffRaffDeployConfig): ValidatedNel[ConfigError, List[Deployment]] = {
    config.deployments.traverseU[ValidatedNel[ConfigError, Deployment]] { case (label, rawDeployment) =>
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
  private[input] def resolveDeployment(label: String, templated: DeploymentOrTemplate, globalStacks: Option[List[String]], globalRegions: Option[List[String]]): ValidatedNel[ConfigError, Deployment] = {
    (Validated.fromOption(templated.`type`, NEL.of(ConfigError(label, "No type field provided"))) |@|
      Validated.fromOption(templated.stacks.orElse(globalStacks).flatMap(NEL.fromList), NEL.of(ConfigError(label, "No stacks provided"))) |@|
      Validated.fromOption(templated.regions.orElse(globalRegions).flatMap(NEL.fromList), NEL.of(ConfigError(label, "No regions provided")))) map { (deploymentType, stacks, regions) =>
      Deployment(
        name = label,
        `type` = deploymentType,
        stacks = stacks,
        regions = regions,
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
        for {
          parentTemplate <- {
            Validated.fromOption(templates.flatMap(_.get(parentTemplateName)),
              NEL.of(ConfigError(templateName, s"Template with name $parentTemplateName does not exist")))
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
        Invalid(NEL.of(ConfigError(label, missingDependencies.mkString(s"Missing deployment dependencies ", ", ", ""))))
    }
  }
}
