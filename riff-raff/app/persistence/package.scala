package persistence

import magenta._
import deployment.DeployFilter
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.DBObject

object `package` {
  implicit def deployFilter2Criteria(filter: DeployFilter) = new {
    def criteria: DBObject = {
      val criteriaList: List[(String, String)] = Nil ++
        filter.projectName.map(("parameters.projectName", _)) ++
        filter.stage.map(("parameters.stage", _)) ++
        filter.deployer.map(("parameters.deployer", _)) ++
        filter.status.map(s => ("status", s.toString)) ++
        filter.task.map(t => ("parameters.deployType", t.toString))
      MongoDBObject(criteriaList)
    }
  }

  implicit def message2MessageDocument(message: Message) = new {
    def asMessageDocument: MessageDocument = MessageDocument(message)
  }
}