package restrictions

import java.util.UUID

trait RestrictionsConfigRepository {
  def getRestrictions(projectName: String): Seq[RestrictionConfig]
  def getRestriction(id: UUID): Option[RestrictionConfig]
}
