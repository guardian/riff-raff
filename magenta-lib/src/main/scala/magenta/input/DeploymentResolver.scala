package magenta.input


object DeploymentResolver {
  val DEFAULT_REGIONS = List("eu-west-1")

  def resolve(config: RiffRaffDeployConfig): List[Either[(String, String), Deployment]] = {
    config.deployments.map { case (label, rawDeployment) =>
      for {
        templated <- applyTemplates(label, rawDeployment, config.templates).right
        deployment <- resolveDeployment(label, templated, config.stacks, config.regions).right
      } yield deployment
    }
  }

  /**
    * Validates and resolves a templated deployment by merging its
    * deployment attributes with any globally defined properties.
    */
  private[input] def resolveDeployment(label: String, templated: DeploymentOrTemplate, globalStacks: Option[List[String]], globalRegions: Option[List[String]]): Either[(String, String), Deployment] = {
    for {
      deploymentType <- templated.`type`.toRight(label -> "No type field provided").right
      stacks <- templated.stacks.orElse(globalStacks).toRight(label -> "No stacks provided").right
    } yield {
      Deployment(
        name              = label,
        `type`            = deploymentType,
        stacks            = stacks,
        regions           = templated.regions.orElse(globalRegions).getOrElse(DEFAULT_REGIONS),
        app               = templated.app.getOrElse(label),
        contentDirectory  = templated.contentDirectory.getOrElse(label),
        dependencies      = templated.dependencies.getOrElse(Nil),
        // TODO? do we need to validate required parameters here,
        // or is that up to the deployment type to do later?
        parameters        = templated.parameters.getOrElse(Map.empty)
      )
    }
  }

  /**
    * Recursively apply named templates by merging the provided
    * deployment template with named parent templates.
    */
  private[input] def applyTemplates(templateName: String, template: DeploymentOrTemplate, templates: Option[Map[String, DeploymentOrTemplate]]): Either[(String, String), DeploymentOrTemplate] = {
    template.template match {
      case None =>
        Right(template)
      case Some(parentTemplateName) =>
        for {
          parentTemplate <- templates.flatMap(_.get(parentTemplateName))
            .toRight(templateName -> s"Template with name $parentTemplateName does not exist").right
          resolvedParent <- applyTemplates(parentTemplateName, parentTemplate, templates).right
        } yield {
          DeploymentOrTemplate(
            `type`            = template.`type`.orElse(resolvedParent.`type`),
            template          = None,
            stacks            = template.stacks.orElse(resolvedParent.stacks),
            regions           = template.regions.orElse(resolvedParent.regions),
            app               = template.app.orElse(resolvedParent.app),
            contentDirectory  = template.contentDirectory.orElse(resolvedParent.contentDirectory),
            dependencies      = template.dependencies.orElse(resolvedParent.dependencies),
            parameters        = Some(resolvedParent.parameters.getOrElse(Map.empty) ++ template.parameters.getOrElse(Map.empty))
          )
        }
    }
  }
}
