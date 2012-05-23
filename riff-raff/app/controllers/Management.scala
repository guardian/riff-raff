package controllers

import play.api.mvc._
import play.api._
import com.gu.management._
import logback.LogbackLevelPage
import com.gu.management.play.InternalManagementPlugin

object Management extends Logging with GlobalSettings {
  val applicationName = Play.current.configuration.getString("application.name").get

  val pages = List(
    new ManifestPage,
    new HealthcheckManagementPage,
    new Switchboard(Switches.all, applicationName),
    StatusPage(applicationName, Metrics.all),
    new LogbackLevelPage(applicationName)
  )

  val plugin = Play.current.plugin[InternalManagementPlugin]
  plugin.foreach { _.registerPages(pages) }
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

