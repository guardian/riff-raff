package postgres

import conf.Config
import persistence.PostgresDatastoreOps
import play.api.db.Databases
import play.api.db.evolutions.Evolutions
import play.api.Configuration

trait PostgresHelpers {
  val config = new Config(Configuration.from(Map(
    "db.default.user" -> "riffraff",
    "db.default.password" -> "riffraff",
    "db.default.url" -> "jdbc:postgresql://localhost:44444/riffraff")
  ).underlying)

  lazy val datastore = {
    val db = new PostgresDatastoreOps(config).buildDatastore()
    applyEvolutions
    db
  }

  private def applyEvolutions = {
    val db = Databases(
      driver = "org.postgresql.Driver",
      url = config.postgres.url,
      name = "default",
      config = Map(
        "username" -> config.postgres.user,
        "password" -> config.postgres.password
      )
    )
    Evolutions.applyEvolutions(db)
  }
}
