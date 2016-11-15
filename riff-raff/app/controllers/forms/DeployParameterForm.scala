package controllers.forms

import magenta.input.{All, DeploymentKey, DeploymentKeysSelector, DeploymentSelector}
import play.api.data.Form
import play.api.data.Forms._
import utils.Forms._

case class DeployParameterForm(project:String, build:String, stage:String, recipe: Option[String], action: String,
  hosts: List[String], stacks: List[String], selectedKeys: List[DeploymentKey], totalKeyCount: Option[Int]) {

  def makeSelector: DeploymentSelector = {
    val keysList =
      if (selectedKeys.isEmpty || totalKeyCount.contains(selectedKeys.size)) {
        None
      } else {
        Some(selectedKeys)
      }
    keysList match {
      case Some(list) => DeploymentKeysSelector(list)
      case None => All
    }
  }
}

object DeployParameterForm {
  val form = Form[DeployParameterForm](
    mapping(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "stage" -> text,
      "recipe" -> optional(text),
      "action" -> nonEmptyText,
      "hosts" -> list(text),
      "stacks" -> list(text),
      "selectedKeys" -> list(deploymentKey),
      "totalKeyCount" -> optional(number)
    )(DeployParameterForm.apply)(DeployParameterForm.unapply)
  )
}
