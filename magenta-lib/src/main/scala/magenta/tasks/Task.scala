package magenta
package tasks

import com.decodified.scalassh.PublicKeyLogin


trait Task {
  // execute this task (should throw on failure)
  def execute(sshCredentials: KeyRing)

  // name of this task: normally no need to override this method
  def name = getClass.getSimpleName

  // end-user friendly description of this task
  // (will normally be prefixed by name before display)
  // This gets displayed a lot, so should be simple
  def description: String

  def fullDescription = name + " " + description

  // A verbose description of this task. For command line tasks,
  //  this should be the full command line to be executed
  def verbose: String

  def taskHost: Option[Host] = None
}
