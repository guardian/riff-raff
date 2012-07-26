import controllers.DeployLibrary
import deployment.DeployInfo
import notification.IrcClient
import play.{Application, GlobalSettings}

class Global extends GlobalSettings {
  override def onStart(app: Application) {
    // initialise message sinks
    IrcClient.init()
    DeployLibrary.init()
    DeployInfo.start()
  }

  override def onStop(app: Application) {
    IrcClient.shutdown()
    DeployLibrary.shutdown()
    DeployInfo.shutdown()
  }
}
