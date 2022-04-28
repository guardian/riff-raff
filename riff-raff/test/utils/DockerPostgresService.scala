package utils

import com.whisk.docker.testkit.{Container, ContainerSpec, DockerReadyChecker, ManagedContainers}
import com.spotify.docker.client.messages.PortBinding
import com.whisk.docker.testkit.scalatest.DockerTestKitForAll
import org.scalatest.Suite

import scala.concurrent.duration._

trait DockerPostgresService extends DockerTestKitForAll { self: Suite =>
  def PostgresAdvertisedPort = 5432
  def PostgresExposedPort = 44444
  val PostgresUser = "riffraff"
  val PostgresPassword = "riffraff"

  val postgresContainer: Container = ContainerSpec("postgres:10.6")
    .withPortBindings((PostgresAdvertisedPort, PortBinding.of("0.0.0.0", PostgresExposedPort)))
    .withEnv(s"POSTGRES_USER=$PostgresUser", s"POSTGRES_PASSWORD=$PostgresPassword")
    .withReadyChecker(
      DockerReadyChecker
        .Jdbc(
          driverClass = "org.postgresql.Driver",
          user = PostgresUser,
          password = Some(PostgresPassword)
        )
        .looped(15, 1.second)
    )
    .toContainer

  override val managedContainers: ManagedContainers = postgresContainer.toManagedContainer
}
