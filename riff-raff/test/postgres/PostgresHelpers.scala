package postgres

import conf.Configuration
import persistence.PostgresDatastore
import play.api.db.Databases
import play.api.db.evolutions.Evolutions
import scalikejdbc.ConnectionPool

trait PostgresHelpers {
//  lazy val datastore = PostgresDatastore.buildDatastore()

  lazy private val url = "jdbc:postgresql://localhost:44444/riffraff"
  lazy private val user = "riffraff"
  lazy private val password = "riffraff"

  lazy val datastore = {
    Class.forName("org.postgresql.Driver")
    ConnectionPool.singleton(url, user, password)

    applyEvolutions

    new PostgresDatastore
  }

  private def applyEvolutions = {
    val db = Databases(
      driver = "org.postgresql.Driver",
      url = url,
      name = "default",
      config = Map(
        "username" -> user,
        "password" -> password
      )
    )
    Evolutions.applyEvolutions(db)
  }
}
