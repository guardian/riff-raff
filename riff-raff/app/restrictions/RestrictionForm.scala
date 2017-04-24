package restrictions

import java.util.UUID

case class RestrictionForm(
    id: UUID,
    projectName: String,
    stage: String,
    editingLocked: Boolean,
    whitelist: Seq[String],
    continuousDeployment: Boolean,
    note: String
)
