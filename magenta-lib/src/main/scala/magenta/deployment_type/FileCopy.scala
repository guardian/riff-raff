package magenta.deployment_type

import magenta.tasks.CopyFile

object FileCopy extends DeploymentType {
  val name = "file"
  val documentation =
    """
      |Copy the package files over to the root directory of a remote host using rsync.
    """.stripMargin

  override def perHostActions = {
    case "deploy" => pkg => host => List(CopyFile("/")(pkg, host))
  }

  def perAppActions = PartialFunction.empty
}
