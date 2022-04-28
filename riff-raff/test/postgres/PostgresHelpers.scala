package postgres

import conf.Config
import org.joda.time.DateTime
import persistence.{PasswordProvider, PostgresDatastoreOps}
import play.api.db.Databases
import play.api.db.evolutions.Evolutions
import play.api.Configuration

trait PostgresHelpers {
  val config = new Config(Configuration.from(Map(
    "db.default.user" -> "riffraff",
    "db.default.password" -> "riffraff",
    "db.default.url" -> "jdbc:postgresql://localhost:7432/riffraff")
  ).underlying, DateTime.now)

  val passwordProvider = new PasswordProvider {
    override def providePassword(): String = "riffraff"
  }

  lazy val datastore = {
    val db = new PostgresDatastoreOps(config, passwordProvider).buildDatastore()
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
        "password" -> passwordProvider.providePassword()
      )
    )
    Evolutions.applyEvolutions(db)
  }
}
