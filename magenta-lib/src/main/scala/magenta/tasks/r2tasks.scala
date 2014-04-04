package magenta.tasks

import magenta.{KeyRing, Host}

case class ExtractToDocroots(host: Host, zipLocation: String, docrootType: String, locationInDocroot: String)
                            (implicit val keyRing: KeyRing) extends RemoteShellTask {
  def commandLine = List("/opt/bin/deploy_static_files.sh", zipLocation, docrootType, locationInDocroot)
}