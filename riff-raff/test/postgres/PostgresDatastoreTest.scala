package postgres

import controllers.{ApiKey, AuthorisationRecord}
import deployment.{DeployFilter, PaginationView}
import org.scalatest.{FreeSpec, Matchers}
import persistence.DeployRecordDocument
import postgres.Generators._
import scalikejdbc._

class PostgresDatastoreTest extends FreeSpec with Matchers with PostgresHelpers {
  "ApiKey table" - {
    def withFixture(test: => Any)= {
      try test
      finally {
        DB localTx { implicit session =>
          sql"DELETE FROM apiKey".update.apply()
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
          val dbApiKey = datastore.getAndUpdateApiKey(apiKey.key, Some("history"))

          dbApiKey shouldBe defined
          apiKey shouldBe dbApiKey.get
        }
      }
    }
  }

  "Auth table" - {
    def withFixture(test: => Any)= {
      try test
      finally {
        DB localTx { implicit session =>
          sql"DELETE FROM auth".update.apply()
        }
      }
    }

    def withAuth(test: AuthorisationRecord => Any) = {
      val auth = someAuth
      datastore.setAuthorisation(auth)
      test(auth)
    }

    "create and read an auth by email" in {
      withFixture {
        withAuth { auth =>
          val dbAuth = datastore.getAuthorisation(auth.email)

          dbAuth shouldBe defined
          auth shouldBe dbAuth.get
        }
      }
    }

    "create and delete auth" in {
      withFixture {
        withAuth { auth =>
          datastore.deleteAuthorisation(auth.email)
          datastore.getAuthorisation(auth.email) shouldBe None
        }
      }
    }
  }

  "Deploy table" - {
    def withFixture(test: => Any)= {
      try test
      finally {
        DB localTx { implicit session =>
          sql"DELETE FROM deploy".update.apply()
        }
      }
    }

    def withDeploys(count: Int = 1)(test: List[DeployRecordDocument] => Any) = {
      val deploys = (0 until count).foldLeft(List.empty[DeployRecordDocument])((acc, _) => acc :+ someDeploy)
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
          val dbDeploys = datastore.getDeploys(None, PaginationView(pageSize = None, page = 1))
          dbDeploys.right.get.size shouldBe 5
        }
      }
    }

    "get deploys using pagination, but no filters" in {
      withFixture {
        withDeploys(3) { _ =>
          val dbDeploysPage1 = datastore.getDeploys(None, PaginationView(pageSize = Some(2), page = 1))
          dbDeploysPage1.right.get.size shouldBe 2

          val dbDeploysPage2 = datastore.getDeploys(None, PaginationView(pageSize = Some(2), page = 2))
          dbDeploysPage2.right.get.size shouldBe 1
        }
      }
    }

    "get deploys using projectName filter, but no pagination" in {
      withFixture {
        withDeploys(2) { deploys =>
          val deploy = deploys.head

          val deployFilter = DeployFilter(projectName = Some(deploy.parameters.projectName))
          val dbDeploys = datastore.getDeploys(Some(deployFilter), PaginationView(pageSize = None, page = 1))

          dbDeploys.right.get.size shouldBe 1
          dbDeploys.right.get.head.parameters.projectName shouldBe deploy.parameters.projectName
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
          val dbDeploys = datastore.getDeploys(Some(deployFilter), PaginationView(None, 1))

          dbDeploys.right.get.size shouldBe 1
          dbDeploys.right.get.head shouldBe deploy
        }
      }
    }
  }
}
