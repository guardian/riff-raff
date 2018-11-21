package migration.data

sealed abstract class MigrationError
final case class InvalidArgs(args: List[String]) extends MigrationError
final case object MissingArgs extends MigrationError
final case class MissingDatabase(uri: String) extends MigrationError
final case class MissingTable(table: String) extends MigrationError
final case class DatabaseError(ex: Throwable) extends MigrationError
final case class ConsoleError(ex: Throwable) extends MigrationError
final case object DecodingError extends MigrationError

