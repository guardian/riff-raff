package notification

// send events after a deploy is complete

/*
configuration looks like

app / stage -> URL???

Initial requirement is to trigger teamcity build:
  When "frontend::article" is deployed to CODE then call a fixed URL

  Similar config to deployinfo?? Regex?

  Make super simple for now?

  Even constrain to only doing build triggering?

  When "frontend::article" is deployed to CODE then start build btNNN
*/
import controllers.{DeployController, Logging}
import magenta._
import akka.actor.{Actor, Props, ActorSystem}
import java.util.UUID
import lifecycle.LifecycleWithoutApp
import persistence.Persistence
import deployment.Task

case class HookCriteria(projectName: String, stage: Stage)
object HookCriteria {
  def apply(parameters:DeployParameters): HookCriteria = HookCriteria(parameters.build.projectName, parameters.stage)
}


object HooksClient extends LifecycleWithoutApp {
  trait Event
  case class Finished(parameters: DeployParameters)

  lazy val system = ActorSystem("notify")
  val actor = try {
    Some(system.actorOf(Props[HooksClient], "hook-client"))
  } catch { case t:Throwable => None }

  def finishedBuild(parameters: DeployParameters) {
    actor.foreach(_ ! Finished(parameters))
  }

  val sink = new MessageSink {
    def message(uuid: UUID, stack: MessageStack) {
      stack.top match {
        case FinishContext(Deploy(parameters)) =>
          if (DeployController.get(uuid).taskType == Task.Deploy)
            finishedBuild(parameters)
        case _ =>
      }
    }
  }

  def init() {
    MessageBroker.subscribe(sink)
  }

  def shutdown() {
    MessageBroker.unsubscribe(sink)
    actor.foreach(system.stop)
  }
}

class HooksClient extends Actor with Logging {
  import HooksClient._

  def receive = {
    case Finished(parameters) =>
      // call mongo to resolve required action
      val action = Persistence.store.getPostDeployHookURL(HookCriteria(parameters))
      // if an action exists then call TeamCity

  }
}

