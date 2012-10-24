package magenta.tasks

import magenta.Host

case class ExtractToDocroots(host: Host, docrootType: String, locationInDocroot: String) extends RemoteShellTask {
  def commandLine = List("/opt/bin/deploy_static_files.sh", docrootType, locationInDocroot)
}