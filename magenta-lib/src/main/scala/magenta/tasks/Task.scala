package magenta
package tasks

import software.amazon.awssdk.services.sts.StsClient

trait Task {
  // execute this task (should throw on failure)
  def execute(reporter: DeployReporter, stopFlag: => Boolean, stsClient: StsClient)
  def execute(reporter: DeployReporter) { execute(reporter, stopFlag = false) }

  def keyRing: KeyRing

  // name of this task: normally no need to override this method
  def name = getClass.getSimpleName

  // end-user friendly description of this task
  // (will normally be prefixed by name before display)
  // This gets displayed a lot, so should be simple
  def description: String

  def fullDescription = name + " " + description

  // A verbose description of this task. For command line tasks,
  //  this should be the full command line to be executed
  def verbose: String = fullDescription
}
