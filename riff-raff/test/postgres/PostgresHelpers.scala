package postgres

import conf.Config
import persistence.PostgresDatastore
import play.api.db.Databases
import play.api.db.evolutions.Evolutions

trait PostgresHelpers {
  lazy val datastore = {
    val db = PostgresDatastore.buildDatastore()
    applyEvolutions
    db
  }

  private def applyEvolutions = {
    val db = Databases(
      driver = "org.postgresql.Driver",
      url = Config.postgres.url,
      name = "default",
      config = Map(
        "username" -> Config.postgres.user,
        "password" -> Config.postgres.password
      )
    )
    Evolutions.applyEvolutions(db)
  }
}
