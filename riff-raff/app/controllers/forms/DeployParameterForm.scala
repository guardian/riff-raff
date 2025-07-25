package controllers.forms

import magenta.Strategy
import magenta.input.{
  All,
  DeploymentKey,
  DeploymentKeysSelector,
  DeploymentSelector
}
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.format.Formatter
import utils.Forms._

case class DeployParameterForm(
    project: String,
    build: String,
    branch: String,
    stage: String,
    action: String,
    selectedKeys: List[DeploymentKey],
    totalKeyCount: Option[Int],
    updateStrategy: Strategy
) {

  def makeSelector: DeploymentSelector = {
    val keysList =
      if (selectedKeys.isEmpty || totalKeyCount.contains(selectedKeys.size)) {
        None
      } else {
        Some(selectedKeys)
      }
    keysList match {
      case Some(list) => DeploymentKeysSelector(list)
      case None       => All
    }
  }
}

object DeployParameterForm {
  val strategyFormat: Formatter[Strategy] = enumeratum.Forms.format(Strategy)

  val form = Form[DeployParameterForm](
    mapping(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "branch" -> text,
      "stage" -> text,
      "action" -> nonEmptyText,
      "selectedKeys" -> list(deploymentKey),
      "totalKeyCount" -> optional(number),
      "updateStrategy" -> of[Strategy](strategyFormat)
    )(DeployParameterForm.apply)(DeployParameterForm.unapply)
  )
}
