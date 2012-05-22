import controllers.Management
import play.api._

object Global extends GlobalSettings {
  override def onStart(app: Application) {
    Logger.info("Application has started: " + Management.applicationName)
  }
}
