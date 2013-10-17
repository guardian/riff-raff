package magenta.packages

import magenta.tasks.UpdateFastlyConfig

object Fastly  extends PackageType {
  val name = "fastly"

  def perAppActions = {
    case "deploy" => pkg => (_, parameters) => List(UpdateFastlyConfig(pkg))
  }

  def params = Seq()
}
