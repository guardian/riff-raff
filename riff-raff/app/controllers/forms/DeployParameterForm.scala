package controllers.forms

import magenta.input.DeploymentId
import play.api.data.Form
import play.api.data.Forms._
import utils.Forms._

case class DeployParameterForm(project:String, build:String, stage:String, recipe: Option[String], action: String,
  hosts: List[String], stacks: List[String], filter: List[DeploymentId], noFilterCount: Option[Int])

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
      "filter" -> list(deploymentId),
      "noFilterCount" -> optional(number)
    )(DeployParameterForm.apply)(DeployParameterForm.unapply)
  )
}
