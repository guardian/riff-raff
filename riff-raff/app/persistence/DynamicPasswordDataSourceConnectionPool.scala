package persistence

import java.sql.{Connection, DriverManager}

import javax.sql.DataSource
import org.apache.commons.dbcp2.{ConnectionFactory, PoolableConnectionFactory, PoolingDataSource}
import org.apache.commons.pool2.impl.GenericObjectPool
import scalikejdbc.{ConnectionPool, ConnectionPoolSettings, DataSourceCloser, DataSourceConnectionPool, DataSourceConnectionPoolSettings, DefaultDataSourceCloser}


trait PasswordProvider {
  def providePassword(): String
}

class DynamicPasswordDataSourceConnectionPool private (
  dataSource: DataSource,
  settings: DataSourceConnectionPoolSettings = DataSourceConnectionPoolSettings(),
  closer: DataSourceCloser = DefaultDataSourceCloser
) extends DataSourceConnectionPool(
  dataSource = dataSource,
  settings = settings,
  closer = closer
)

object DynamicPasswordDataSourceConnectionPool {
  def apply(
    url: String,
    user: String,
    passwordProvider: PasswordProvider,
    settings: DataSourceConnectionPoolSettings = DataSourceConnectionPoolSettings(),
    closer: DataSourceCloser = DefaultDataSourceCloser
  ): DynamicPasswordDataSourceConnectionPool = {
    val connectionFactory = new ConnectionFactory {
      override def createConnection(): Connection = {
        // This is where the trick is, every time the pool try to create a new connection (via the connection factory)
        // we go and fetch a new IAM password
        DriverManager.getConnection(url, user, passwordProvider.providePassword())
      }
    }

    val poolableConnectionFactory = new PoolableConnectionFactory(connectionFactory, null)
    val connectionPool = new GenericObjectPool(poolableConnectionFactory)
    poolableConnectionFactory.setPool(connectionPool)
    val dataSource = new PoolingDataSource(connectionPool)
    new DynamicPasswordDataSourceConnectionPool(dataSource, settings, closer)
  }
}
