package magenta.packages

import magenta.tasks.CopyFile

object FileCopy extends PackageType {
  val name = "file"

  override def perHostActions = {
    case "deploy" => pkg => host => List(CopyFile(host, pkg.srcDir.getPath, "/"))
  }

  def params = Seq()

  def perAppActions = PartialFunction.empty
}
