package conf

import play.api.Play
import play.api.mvc.{Action, Result, AnyContent, Request}
import com.gu.management._
import logback.LogbackLevelPage
import com.gu.management.play.{ Management => PlayManagement }
import com.gu.conf.ConfigurationFactory
import java.io.File


class Configuration(val application: String, val webappConfDirectory: String = "env") {
  protected val configuration = ConfigurationFactory.getConfiguration(application, webappConfDirectory)

  object sshKey {
    lazy val path: String = configuration.getStringProperty("sshKey.path").getOrElse {
      throw new IllegalStateException("No private SSH key configured")
    }
    lazy val file: File = new File(path)
  }

  object logging {
    lazy val verbose = configuration.getStringProperty("logging").map(_.equalsIgnoreCase("VERBOSE")).getOrElse(false)
  }

  object s3 {
    lazy val accessKey = configuration.getStringProperty("s3.accessKey").getOrElse {
      throw new IllegalStateException("No S3 access key configured")
    }
    lazy val secretAccessKey = configuration.getStringProperty("s3.secretAccessKey").getOrElse {
      throw new IllegalStateException("No S3 secret access key configured")
    }
  }

  override def toString(): String = configuration.toString
}

object Configuration extends Configuration("riff-raff", webappConfDirectory = "env")

object Management extends PlayManagement {
  val applicationName = Play.current.configuration.getString("application.name").get

  val pages = List(
    new ManifestPage,
    new HealthcheckManagementPage,
    new Switchboard(Switches.all, applicationName),
    StatusPage(applicationName, Metrics.all),
    new LogbackLevelPage(applicationName)
  )
}

class TimingAction(group: String, name: String, title: String, description: String, master: Option[Metric] = None)
  extends TimingMetric(group, name, title, description, master) {

  def apply(f: Request[AnyContent] => Result): Action[AnyContent] = {
    Action {
      request =>
        measure {
          f(request)
        }
    }
  }
  def apply(f: => Result): Action[AnyContent] = {
    Action {
      measure {
        f
      }
    }
  }
}

object TimedAction extends TimingAction("webapp",
  "requests",
  "Requests",
  "Count and response time of requests")

object TimedCometAction extends TimingAction("webapp",
  "comet_requests",
  "Comet Requests",
  "Count and response time of comet requests")

object LoginCounter extends CountMetric("webapp",
  "login_attempts",
  "Login attempts",
  "Number of attempted logins")

object FailedLoginCounter extends CountMetric("webapp",
  "failed_logins",
  "Failed logins",
  "Number of failed logins")

object Metrics {
  val all: Seq[Metric] = Seq(TimedAction, TimedCometAction, LoginCounter, FailedLoginCounter)
}

object Switches {
  //  val switch = new DefaultSwitch("name", "Description Text")
  val all: Seq[Switchable] = List(Healthcheck.switch)
}

