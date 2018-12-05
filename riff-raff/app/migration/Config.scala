package migration

final case class Config(
  mongoUri: String,
  pgUri: String,
  pgUser: String,
  pgPassword: String,
  tables: List[String], 
  limit: Option[Int], 
  dryRun: Boolean
)

