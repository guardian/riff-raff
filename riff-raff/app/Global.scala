import controllers.DeployLibrary
import notification.IrcClient
import play.{Application, GlobalSettings}
import utils.ScheduledAgent

class Global extends GlobalSettings {
  override def onStart(app: Application) {
    // initialise message sinks
    IrcClient.init()
    DeployLibrary.init()
  }

  override def onStop(app: Application) {
    IrcClient.shutdown()
    DeployLibrary.shutdown()
    ScheduledAgent.shutdown()
  }
}
