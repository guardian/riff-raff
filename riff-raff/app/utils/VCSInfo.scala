package utils

import java.net.{URI, URL}

import controllers.Logging

trait VCSInfo {
  def name: String
  def revision: String
  def ciVcsUrl: String
  def repo: String
  def baseUrl: URI
  def commitUrl: URI
  def treeUrl: URI
  def headUrl: URI

  import VCSInfo._
  def map: Map[String,String] = Map(
    REVISION -> revision,
    CIURL -> ciVcsUrl,
    NAME -> name,
    REPO -> repo,
    BASEURL -> baseUrl.toString,
    COMMITURL -> commitUrl.toString,
    TREEURL -> treeUrl.toString,
    HEADURL -> headUrl.toString
  )
}

case class GitHub(ciVcsUrl: String, revision: String, repo: String) extends VCSInfo {
  lazy val name = "GitHub"
  lazy val baseUrl = new URI(s"https://github.com/$repo")
  lazy val commitUrl = new URI(s"$baseUrl/commit/$revision")
  lazy val treeUrl = new URI(s"$baseUrl/tree/$revision")
  lazy val headUrl = baseUrl
}

object VCSInfo extends Logging {
  val REVISION = "vcsRevision"
  val CIURL = "vcsUrl"
  val NAME = "vcsName"
  val REPO = "vcsRepo"
  val BASEURL = "vcsBaseUrl"
  val COMMITURL = "vcsCommitUrl"
  val TREEURL = "vcsTreeUrl"
  val HEADURL = "vcsHeadUrl"

  val GitHubProtocol = """git://github\.com/(.*)\.git""".r
  val GitHubUser = """git@github\.com:(.*)\.git""".r
  val GitHubWeb = """https://github\.com/(.*?)(?:.git)?$""".r

  def apply(ciVcsUrl: String, revision: String): Option[VCSInfo] = {
    log.debug("url:%s revision:%s" format(ciVcsUrl, revision))
    ciVcsUrl match {
      case GitHubProtocol(repo) => Some(GitHub(ciVcsUrl, revision, repo))
      case GitHubUser(repo) => Some(GitHub(ciVcsUrl, revision, repo))
      case GitHubWeb(repo) => Some(GitHub(ciVcsUrl, revision, repo))
      case _ => None
    }
  }

  def apply(metaData: Map[String, String]): Option[VCSInfo] = {
    metaData.get(REVISION).flatMap{ revision =>
      metaData.get(CIURL).flatMap { url =>
        VCSInfo(url, revision)
      }
    }
  }

  def normalise(url: String): Option[String] = {
    url match {
      case GitHubProtocol(path) => Some(path)
      case GitHubUser(path) => Some(path)
      case GitHubWeb(path) => Some(path)
      case _ => None
    }
  }
}
