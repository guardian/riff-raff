package restrictions

import com.gu.googleauth.UserIdentity
import deployment.{ContinuousDeploymentRequestSource, Error, RequestSource, UserRequestSource}

object RestrictionChecker {
  /** Method that checks whether the supplied config is editable by the supplied source and reports and error reason if
    * not.
    * @param currentConfig The config to check - note that when a config is being checked this must be the original
    *                      config not the updated config
    * @param identity The identity of the user trying to make the edit
    * @return Either true or a string containing the reason it is not editable
    */
  def isEditable(currentConfig: Option[RestrictionConfig], identity: UserIdentity, superusers: List[String]): Either[Error, Boolean] = {
    currentConfig match {
      case None => Right(true)
      case Some(config) if !config.editingLocked || config.email == identity.email || superusers.contains(identity.email) => Right(true)
      case Some(config) =>
        val superuserInfo = if (superusers.nonEmpty) s" - can also be modified by ${superusers.mkString(", ")}" else ""
        Left(Error(s"Locked by ${config.email}$superuserInfo"))
    }
  }

  def configsThatPreventDeployment(restrictions: RestrictionsConfigRepository, projectName: String, stage: String,
    source: RequestSource): Seq[RestrictionConfig] = {
    for {
      restriction <- restrictions.getRestrictions(projectName)
      if stageMatches(restriction.stage, stage)
      if !sourceMatches(restriction, source)
    } yield restriction
  }

  def sourceMatches(config: RestrictionConfig, source: RequestSource): Boolean = {
    source match {
      case ContinuousDeploymentRequestSource => config.continuousDeployment
      case UserRequestSource(identity) => config.whitelist.contains(identity.email)
      case _ => false
    }
  }

  def stageMatches(configStage: String, stage: String): Boolean =
    if (configStage.matches(""".*[$^.+*?()\[{|].*""")) {
      stage.matches(configStage)
    } else stage == configStage
}
