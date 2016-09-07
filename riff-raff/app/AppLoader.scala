import play.api.{Application, ApplicationLoader}
import play.api.ApplicationLoader.Context

class AppLoader extends ApplicationLoader {

  override def load(context: Context): Application = {
    val components = new AppComponents(context)
    // TODO set up lifecycles here?
    components.application
  }

}
