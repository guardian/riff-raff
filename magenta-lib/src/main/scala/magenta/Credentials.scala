package magenta

import java.io.File

sealed trait Credentials {
  def keyFile: Option[File]
}

case class PassphraseProvided(user: String, passphrase: String, keyFile: Option[File]) extends Credentials

case class SystemUser(keyFile: Option[File]) extends Credentials
