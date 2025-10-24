package postgres

import controllers.ApiKey
import deployment.{DeployFilter, PaginationView}
import magenta.RunState.ChildRunning
import magenta.{TaskDetail, ThrowableDetail}
import org.joda.time.DateTime
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import persistence.{
  DeployDocument,
  DeployRecordDocument,
  FailDocument,
  LogDocument,
  TaskListDocument
}
import postgres.TestData._
import scalikejdbc._

class PostgresDatastoreTest
    extends AnyFreeSpec
    with Matchers
    with PostgresHelpers {
  "ApiKey table" - {
    def withFixture(test: => Any) = {
      try test
      finally {
        DB localTx { implicit session =>
          sql"DELETE FROM apiKey".update().apply()
        }
      }
    }

    def withApiKey(test: ApiKey => Any) = {
      val apiKey = someApiKey
      datastore.createApiKey(apiKey)
      test(apiKey)
    }

    "create and read an api key by key" in {
      withFixture {
        withApiKey { apiKey =>
          val dbApiKey = datastore.getApiKey(apiKey.key)

          dbApiKey shouldBe defined
          apiKey shouldBe dbApiKey.get
        }
      }
    }

    "create and read an api key by application" in {
      withFixture {
        withApiKey { apiKey =>
          val dbApiKey = datastore.getApiKeyByApplication(apiKey.application)

          dbApiKey shouldBe defined
          apiKey shouldBe dbApiKey.get
        }
      }
    }

    "create and update an api key" in {
      withFixture {
        withApiKey { apiKey =>
          val dbApiKey =
            datastore.getAndUpdateApiKey(apiKey.key, Some("history"))

          dbApiKey shouldBe defined
          dbApiKey.get.callCounters shouldBe Map("history" -> 11)
        }
      }
    }
  }

  "Deploy table" - {
    def withFixture(test: => Any) = {
      try test
      finally {
        DB localTx { implicit session =>
          sql"DELETE FROM deploy".update().apply()
        }
      }
    }

    def withDeploys(count: Int = 1)(test: List[DeployRecordDocument] => Any) = {
      val deploys =
        (0 until count).foldLeft(List.empty[DeployRecordDocument])((acc, _) =>
          acc :+ someDeploy
        )
      deploys.foreach(datastore.writeDeploy)
      test(deploys)
    }

    "create and read a deploy" in {
      withFixture {
        withDeploys() { deploys =>
          val dbDeploy = datastore.readDeploy(deploys.head.uuid)

          dbDeploy shouldBe defined
          deploys.head shouldBe dbDeploy.get
        }
      }
    }

    "get deploys using no filters and no pagination" in {
      withFixture {
        withDeploys(5) { _ =>
          val dbDeploys = datastore.getDeploys(
            None,
            PaginationView(pageSize = None, page = 1)
          )
          dbDeploys.toOption.get.size shouldBe 5
        }
      }
    }

    "get deploys using pagination, but no filters" in {
      withFixture {
        withDeploys(3) { _ =>
          val dbDeploysPage1 = datastore.getDeploys(
            None,
            PaginationView(pageSize = Some(2), page = 1)
          )
          dbDeploysPage1.toOption.get.size shouldBe 2

          val dbDeploysPage2 = datastore.getDeploys(
            None,
            PaginationView(pageSize = Some(2), page = 2)
          )
          dbDeploysPage2.toOption.get.size shouldBe 1
        }
      }
    }

    "get deploys using projectName filter, but no pagination" in {
      withFixture {
        withDeploys(2) { deploys =>
          val deploy = deploys.head

          val deployFilter =
            DeployFilter(projectName = Some(deploy.parameters.projectName))
          val dbDeploys = datastore.getDeploys(
            Some(deployFilter),
            PaginationView(pageSize = None, page = 1)
          )

          dbDeploys.toOption.get.size shouldBe 1
          dbDeploys.toOption.get.head.parameters.projectName shouldBe deploy.parameters.projectName
        }
      }
    }

    "get deploys using partial projectName filter" in {
      withFixture {
        withDeploys(2) { deploys =>
          val deploy = deploys.head

          val deployFilter = DeployFilter(projectName = Some("project-name"))
          val dbDeploys = datastore.getDeploys(
            Some(deployFilter),
            PaginationView(pageSize = Some(20), page = 1)
          )

          dbDeploys.toOption.get.size shouldBe 2
          dbDeploys.toOption.get.head.parameters.projectName
            .startsWith("project-name") shouldBe true
        }
      }
    }

    "get deploys using all filters, but no pagination" in {
      withFixture {
        withDeploys(2) { deploys =>
          val deploy = deploys.head

          val deployFilter = DeployFilter(
            projectName = Some(deploy.parameters.projectName),
            stage = Some(deploy.parameters.stage),
            deployer = Some(deploy.parameters.deployer),
            status = Some(deploy.status),
            maxDaysAgo = None,
            hasWarnings = deploy.hasWarnings
          )
          val dbDeploys =
            datastore.getDeploys(Some(deployFilter), PaginationView(None, 1))

          dbDeploys.toOption.get.size shouldBe 1
          dbDeploys.toOption.get.head shouldBe deploy
        }
      }
    }

    "update the status of a deploy" in {
      withFixture {
        withDeploys() { deploys =>
          datastore.updateStatus(deploys.head.uuid, ChildRunning)

          val dbDeploy = datastore.readDeploy(deploys.head.uuid)

          dbDeploy shouldBe defined
          dbDeploy.get.status shouldBe ChildRunning
        }
      }
    }

    "update the deploy summary of a deploy" in {
      withFixture {
        withDeploys() { deploys =>
          val completedTasks = 11
          val totalTasks = Some(20)
          val lastActivityTime = DateTime.now()
          val hasWarnings = true

          datastore.updateDeploySummary(
            deploys.head.uuid,
            totalTasks,
            completedTasks,
            lastActivityTime,
            hasWarnings
          )

          val dbDeploy = datastore.readDeploy(deploys.head.uuid)

          dbDeploy shouldBe defined
          dbDeploy.get.completedTasks.get shouldBe completedTasks
          dbDeploy.get.totalTasks shouldBe totalTasks
          dbDeploy.get.lastActivityTime.get shouldBe lastActivityTime
          dbDeploy.get.hasWarnings.get shouldBe hasWarnings
        }
      }
    }

    "count deploys using filters" in {
      withFixture {
        withDeploys(5) { deploys =>
          val deploy = deploys.head

          val stageDeployFilter =
            DeployFilter(stage = Some(deploy.parameters.stage))

          val dbDeploys = datastore.countDeploys(Some(stageDeployFilter))

          dbDeploys shouldBe 5

          val allDeployFilters = DeployFilter(
            projectName = Some(deploy.parameters.projectName),
            stage = Some(deploy.parameters.stage),
            deployer = Some(deploy.parameters.deployer),
            status = Some(deploy.status),
            maxDaysAgo = None,
            hasWarnings = deploy.hasWarnings
          )
          val dbDeploy = datastore.countDeploys(Some(allDeployFilters))

          dbDeploy shouldBe 1
        }
      }
    }

    "get complete deploys older than date" in {
      withFixture {
        withDeploys(5) { _ =>
          val dbDeploys = datastore.getCompleteDeploysOlderThan(DateTime.now())
          dbDeploys.size shouldBe 5
        }
      }
    }

    "find projects" in {
      withFixture {
        withDeploys(5) { _ =>
          datastore.findProjects().toOption.get.size shouldBe 5
        }
      }
    }

    "get latest completed deploys for project" in {
      withFixture {
        withDeploys(3) { deploys =>
          val projectName = deploys.head.parameters.projectName

          val newDeploy = someDeploy
          val modifiedNewDeploy = newDeploy.copy(
            startTime = DateTime.now(),
            parameters = newDeploy.parameters.copy(projectName = projectName)
          )
          datastore.writeDeploy(modifiedNewDeploy)

          val anotherDeploy = someDeploy
          val modifiedDeploy = anotherDeploy.copy(
            startTime = DateTime.now(),
            parameters = anotherDeploy.parameters
              .copy(projectName = projectName, stage = "CODE")
          )
          datastore.writeDeploy(modifiedDeploy)

          val result = datastore.getLastCompletedDeploys(projectName)

          result.size shouldBe 2
          result.keys shouldBe Set("TEST", "CODE")
          result("TEST") shouldBe newDeploy.uuid
          result("CODE") shouldBe modifiedDeploy.uuid
        }
      }
    }

    "summarise deploy deploy" in {
      withFixture {
        withDeploys() { deploys =>
          datastore.summariseDeploy(deploys.head.uuid)
          val dbDeploy = datastore.readDeploy(deploys.head.uuid)

          dbDeploy.get.summarised.get shouldBe true
        }
      }
    }
  }

  "DeployLog table" - {
    def withFixture(test: => Any) = {
      try test
      finally {
        DB localTx { implicit session =>
          sql"DELETE FROM deployLog".update().apply()
        }
      }
    }

    def withLogDocument(logDocument: LogDocument)(test: LogDocument => Any) = {
      datastore.writeLog(logDocument)
      test(logDocument)
    }

    "read a deploy log with a DeployDocument document type" in {
      val deployLog = someLogDocument(DeployDocument)

      withFixture {
        withLogDocument(deployLog) { logDoc =>
          datastore.readLogs(logDoc.deploy).head shouldBe logDoc
        }
      }
    }

    "read a deploy log with a TaskListDocument document type" in {
      withFixture {
        val deployLog = someLogDocument(
          TaskListDocument(
            List(
              TaskDetail("name1", "description1", "verbose1"),
              TaskDetail("name2", "description2", "verbose2")
            )
          )
        )

        withLogDocument(deployLog) { logDoc =>
          datastore.readLogs(logDoc.deploy).head shouldBe logDoc
        }
      }
    }

    "read a deploy log with a FailDocument document type" in {
      withFixture {
        val deployLog = someLogDocument(
          FailDocument("fail", ThrowableDetail("name", "message", "stacktrace"))
        )

        withLogDocument(deployLog) { logDoc =>
          datastore.readLogs(logDoc.deploy).head shouldBe logDoc
        }
      }
    }
  }
}
