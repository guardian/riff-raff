package magenta.tasks

import magenta.Host

case class ExtractToDocroots(host: Host, zipLocation: String, docrootType: String, locationInDocroot: String) extends RemoteShellTask {
  def commandLine = List("/opt/bin/deploy_static_files.sh", zipLocation, docrootType, locationInDocroot)
}