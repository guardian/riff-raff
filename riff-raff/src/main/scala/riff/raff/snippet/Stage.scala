package riff.raff.snippet

import net.liftweb._
import http.S
import net.liftweb.util.Helpers._
import riff.raff.Config
import com.gu.deploy.App

class Stage {
  lazy val stage = S.param("stage").openOr("CODE")

  def name = "* *" #> stage

  def detail = {
    val hostsInThisStage = Config.parsedDeployInfo
      .filter(_.stage == stage)

    val allApps = hostsInThisStage
      .map(_.apps)
      .fold(Set.empty)(_ ++ _)
      .toList
      .sortBy(_.name)

    def hostsForApp(app: App) = hostsInThisStage.filter(_.apps contains app)

    "*" #> allApps.map { app =>
      ".app-name" #> <strong>{app.name}</strong> &
      "tr" #> hostsForApp(app).map { host =>
        ".host *" #> host.name &
        ".version *" #> "(unknown version)"
      }
    }

    //Config.parsedDeployInfo.toString
  }
}