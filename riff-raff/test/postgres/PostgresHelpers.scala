package postgres

import conf.Configuration
import persistence.PostgresDatastore
import play.api.db.Databases
import play.api.db.evolutions.Evolutions
import scalikejdbc.ConnectionPool

trait PostgresHelpers {
//  lazy val datastore = PostgresDatastore.buildDatastore()
  lazy val datastore = {
    Class.forName("org.postgresql.Driver")
    ConnectionPool.singleton("jdbc:postgresql://localhost:44444/riffraff", "riffraff", "riffraff")

    val db = Databases(
      driver = "org.postgresql.Driver",
      url = "jdbc:postgresql://localhost:44444/riffraff",
      name = "default",
      config = Map(
        "username" -> "riffraff",
        "password" -> "riffraff"
      )
    )
    Evolutions.applyEvolutions(db)

    new PostgresDatastore
  }
}
