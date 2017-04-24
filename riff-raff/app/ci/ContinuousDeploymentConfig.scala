package ci

import java.util.UUID
import org.joda.time.DateTime
import persistence.{MongoFormat, MongoSerialisable}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.Implicits._

object Trigger extends Enumeration {
  type Mode = Value
  val SuccessfulBuild = Value(1, "Successful build")
  val Disabled = Value(0, "Disabled")
}

case class ContinuousDeploymentConfig(
    id: UUID,
    projectName: String,
    stage: String,
    recipe: String,
    branchMatcher: Option[String],
    trigger: Trigger.Mode,
    user: String,
    lastEdited: DateTime = new DateTime()
) {
  lazy val branchRE = branchMatcher.map(re => "^%s$".format(re).r).getOrElse(".*".r)
  lazy val buildFilter =
    (build: CIBuild) => build.jobName == projectName && branchRE.findFirstMatchIn(build.branchName).isDefined

  def findMatchOnSuccessfulBuild(build: CIBuild): Option[CIBuild] = {
    if (trigger == Trigger.SuccessfulBuild && buildFilter(build))
      Some(build)
    else None
  }
}

object ContinuousDeploymentConfig extends MongoSerialisable[ContinuousDeploymentConfig] {
  implicit val configFormat: MongoFormat[ContinuousDeploymentConfig] = new ConfigMongoFormat
  private class ConfigMongoFormat extends MongoFormat[ContinuousDeploymentConfig] {
    def toDBO(a: ContinuousDeploymentConfig) = {
      val values = Map(
        "_id" -> a.id,
        "projectName" -> a.projectName,
        "stage" -> a.stage,
        "recipe" -> a.recipe,
        "triggerMode" -> a.trigger.id,
        "user" -> a.user,
        "lastEdited" -> a.lastEdited
      ) ++
        (a.branchMatcher map ("branchMatcher" -> _))
      values.toMap
    }

    def fromDBO(dbo: MongoDBObject) = {
      val enabledDB = dbo.getAs[Boolean]("enabled")
      val triggerDB = dbo.getAs[Int]("triggerMode")
      val triggerMode = (enabledDB, triggerDB) match {
        case (_, Some(triggerModeId)) => Trigger(triggerModeId)
        case (Some(true), None) => Trigger.SuccessfulBuild
        case (Some(false), None) => Trigger.Disabled
        case _ => Trigger.Disabled
      }

      Some(
        ContinuousDeploymentConfig(
          id = dbo.as[UUID]("_id"),
          projectName = dbo.as[String]("projectName"),
          stage = dbo.as[String]("stage"),
          recipe = dbo.as[String]("recipe"),
          trigger = triggerMode,
          user = dbo.as[String]("user"),
          lastEdited = dbo.as[DateTime]("lastEdited"),
          branchMatcher = dbo.getAs[String]("branchMatcher")
        ))

    }
  }
}
