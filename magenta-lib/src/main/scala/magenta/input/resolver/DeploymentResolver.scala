package magenta.input.resolver

import magenta.input.{ConfigError, Deployment, DeploymentOrTemplate, RiffRaffDeployConfig}

object DeploymentResolver {
  def resolve(config: RiffRaffDeployConfig): List[Either[ConfigError, Deployment]] = {
    config.deployments.map { case (label, rawDeployment) =>
      for {
        templated <- applyTemplates(label, rawDeployment, config.templates).right
        deployment <- resolveDeployment(label, templated, config.stacks, config.regions).right
        _ <- validateDependencies(label, deployment, config.deployments).right
      } yield deployment
    }
  }

  /**
    * Validates and resolves a templated deployment by merging its
    * deployment attributes with any globally defined properties.
    */
  private[input] def resolveDeployment(label: String, templated: DeploymentOrTemplate, globalStacks: Option[List[String]], globalRegions: Option[List[String]]): Either[ConfigError, Deployment] = {
    for {
      deploymentType <- templated.`type`.toRight(ConfigError(label, "No type field provided")).right
      stacks <- templated.stacks.orElse(globalStacks).toRight(ConfigError(label, "No stacks provided")).right
      regions <- templated.regions.orElse(globalRegions).toRight(ConfigError(label, "No regions provided")).right
    } yield {
      Deployment(
        name              = label,
        `type`            = deploymentType,
        stacks            = stacks,
        regions           = regions,
        actions           = templated.actions,
        app               = templated.app.getOrElse(label),
        contentDirectory  = templated.contentDirectory.getOrElse(label),
        dependencies      = templated.dependencies.getOrElse(Nil),
        parameters        = templated.parameters.getOrElse(Map.empty)
      )
    }
  }

  /**
    * Recursively apply named templates by merging the provided
    * deployment template with named parent templates.
    */
  private[input] def applyTemplates(templateName: String, template: DeploymentOrTemplate, templates: Option[Map[String, DeploymentOrTemplate]]): Either[ConfigError, DeploymentOrTemplate] = {
    template.template match {
      case None =>
        Right(template)
      case Some(parentTemplateName) =>
        for {
          parentTemplate <- templates.flatMap(_.get(parentTemplateName))
            .toRight(ConfigError(templateName, s"Template with name $parentTemplateName does not exist")).right
          resolvedParent <- applyTemplates(parentTemplateName, parentTemplate, templates).right
        } yield {
          DeploymentOrTemplate(
            `type`            = template.`type`.orElse(resolvedParent.`type`),
            template          = None,
            stacks            = template.stacks.orElse(resolvedParent.stacks),
            regions           = template.regions.orElse(resolvedParent.regions),
            actions           = template.actions.orElse(resolvedParent.actions),
            app               = template.app.orElse(resolvedParent.app),
            contentDirectory  = template.contentDirectory.orElse(resolvedParent.contentDirectory),
            dependencies      = template.dependencies.orElse(resolvedParent.dependencies),
            parameters        = Some(resolvedParent.parameters.getOrElse(Map.empty) ++ template.parameters.getOrElse(Map.empty))
          )
        }
    }
  }

  /**
    * Ensures that when deployments have named dependencies, deployments with those names exists.
    */
  private[input] def validateDependencies(label: String, deployment: Deployment, allDeployments: List[(String, DeploymentOrTemplate)]): Either[ConfigError, Deployment] = {
    val allDeploymentNames = allDeployments.map { case (name, _) => name }
    deployment.dependencies.filterNot(allDeploymentNames.contains) match {
      case Nil =>
        Right(deployment)
      case missingDependencies =>
        Left(ConfigError(label, missingDependencies.mkString(s"Missing deployment dependencies ", ", ", "")))
    }
  }
}
