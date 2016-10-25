package restrictions

import com.gu.googleauth.UserIdentity
import deployment.{ContinuousDeploymentRequestSource, RequestSource, UserRequestSource}

object RestrictionChecker {
  /** Method that runs one function if the supplied config is editable by the supplied source and another if it is not
    * @param currentConfig The config to check - note that when a config is being checked this must be the original
    *                      config not the updated config
    * @param identity The identity of the user trying to make the edit
    * @param ifEditable The function whose result will be returned if we consider the config to be editable
    * @param ifNotEditable The function whose result will be returned if the config cannot be edited
    * @tparam T The function return type
    * @return The result of either ifEditable or ifNotEditable
    */
  def editable[T](currentConfig: Option[RestrictionConfig], identity: UserIdentity)
    (ifEditable: => T)(ifNotEditable: String => T): T = {
    currentConfig match {
      case None =>
        ifEditable
      case Some(config) if !config.editingLocked || config.email == identity.email =>
        ifEditable
      case Some(config) =>
        ifNotEditable(s"Locked by ${identity.fullName}")
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
