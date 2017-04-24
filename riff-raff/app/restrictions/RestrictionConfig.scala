package restrictions

import java.util.UUID

import org.joda.time.DateTime

/**
  * A configuration that restricts which deploys are allowed
  * @param id Unique ID for CRUD storage and retrival
  * @param projectName The project name that this applies to
  * @param stage The stage that this applies to (this can be a regex, but if no special regex characters are found then
  *              it will be treated as a literal string
  * @param lastEdited The date time that this was last updated
  * @param fullName The user that last updated this
  * @param email The authed email of the user that last updated this
  * @param editingLocked Whether editing of this record should be locked to the user that created it
  * @param whitelist A whitelist of users that can deploy
  * @param continuousDeployment Whether continuous deploys can start this deploy
  * @param note Note explaining why thie restriction is in place
  */
case class RestrictionConfig(
    id: UUID,
    projectName: String,
    stage: String,
    lastEdited: DateTime,
    fullName: String,
    email: String,
    editingLocked: Boolean,
    whitelist: Seq[String],
    continuousDeployment: Boolean,
    note: String
)
