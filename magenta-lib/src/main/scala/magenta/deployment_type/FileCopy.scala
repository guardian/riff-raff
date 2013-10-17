package magenta.deployment_type

import magenta.tasks.CopyFile

object FileCopy extends DeploymentType {
  val name = "file"

  override def perHostActions = {
    case "deploy" => pkg => host => List(CopyFile(host, pkg.srcDir.getPath, "/"))
  }

  def params = Seq()

  def perAppActions = PartialFunction.empty
}
