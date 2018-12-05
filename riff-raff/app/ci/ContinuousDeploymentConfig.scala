package ci

import java.util.UUID
import org.joda.time.DateTime

object Trigger extends Enumeration {
  type Mode = Value
  val SuccessfulBuild = Value(1, "Successful build")
  val Disabled = Value(0, "Disabled")
}

case class ContinuousDeploymentConfig(
                                       id: UUID,
                                       projectName: String,
                                       stage: String,
                                       branchMatcher:Option[String],
                                       trigger: Trigger.Mode,
                                       user: String,
                                       lastEdited: DateTime = new DateTime()
                                       ) {
  lazy val branchRE = branchMatcher.map(re => "^%s$".format(re).r).getOrElse(".*".r)
  lazy val buildFilter =
    (build:CIBuild) => build.jobName == projectName && branchRE.findFirstMatchIn(build.branchName).isDefined

  def findMatchOnSuccessfulBuild(build: CIBuild): Option[CIBuild] = {
    if (trigger == Trigger.SuccessfulBuild && buildFilter(build))
      Some(build)
    else None
  }
}

