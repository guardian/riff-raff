package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.joda.time.DateTime
import magenta._
import persistence._
import deployment.DeployRecord
import deployment.Task
import java.util.UUID
import org.bson.BasicBSONEncoder
import java.io.File
import com.mongodb.util.JSON
import com.mongodb.DBObject
import com.mongodb.casbah.commons.MongoDBObject

class MappingTest extends FlatSpec with ShouldMatchers with Utilities {
  lazy val graters = new RiffRaffGraters {
    def loader = Some(getClass.getClassLoader)
  }
}
