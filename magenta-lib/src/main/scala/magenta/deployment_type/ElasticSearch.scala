package magenta.deployment_type

import magenta.tasks._
import java.io.File

object ElasticSearch extends DeploymentType {
  def name = "elasticsearch"

  val params = Seq(bucket, secondsToWait)
  val bucket = Param[String]("bucket")
  val secondsToWait = Param("secondsToWait", Some(15 * 60))

  //  Params(
  //    bucket[String],
  //    maxWait(15.minutes)
  //  )

  def perAppActions = {
    case "deploy" => (pkg) => (_, parameters) => {
      List(
        TagCurrentInstancesWithTerminationTag(name, parameters.stage),
        DoubleSize(name, parameters.stage),
        WaitForElasticSearchClusterGreen(name, parameters.stage, secondsToWait(pkg) * 1000),
        CullElasticSearchInstancesWithTerminationTag(name, parameters.stage, secondsToWait(pkg) * 1000)
      )
    }
    case "uploadArtifacts" => (pkg) => (_, parameters) =>
      List(
        S3Upload(parameters.stage, bucket(pkg), new File(pkg.srcDir.getPath + "/"))
      )
  }
}
