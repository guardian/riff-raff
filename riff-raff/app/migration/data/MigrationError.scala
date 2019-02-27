package migration.data

sealed abstract class MigrationError
final case class MissingTable(table: String) extends MigrationError
final case class DatabaseError(ex: Throwable) extends MigrationError

