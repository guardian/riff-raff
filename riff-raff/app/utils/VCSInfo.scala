package utils

import java.net.URL
import controllers.Logging

trait VCSInfo {
  def name: String
  def revision: String
  def ciVcsUrl: String
  def repo: String
  def baseUrl: URL
  def commitUrl: URL
  def treeUrl: URL
  def headUrl: URL

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

case class GuGit(ciVcsUrl: String, revision: String, repo: String) extends VCSInfo {
  lazy val name = "Git.gudev.gnl"
  lazy val baseUrl = new URL("http://git.gudev.gnl/%s.git" format repo)
  lazy val commitUrl = new URL("%s/commitdiff/%s" format (baseUrl, revision))
  lazy val treeUrl = new URL("%s/tree/%s" format (baseUrl, revision))
  lazy val headUrl = baseUrl
}

case class GitHub(ciVcsUrl: String, revision: String, repo: String) extends VCSInfo {
  lazy val name = "GitHub"
  lazy val baseUrl: URL = new URL("https://github.com/%s" format repo)
  lazy val commitUrl = new URL("%s/commit/%s" format (baseUrl, revision))
  lazy val treeUrl = new URL("%s/tree/%s" format (baseUrl, revision))
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

  val GuGitFile = """file:///git/(.*)\.git""".r
  val GitHubProtocol = """git://github\.com/(.*)\.git""".r
  val GitHubUser = """git@github\.com:(.*)\.git""".r

  def apply(ciVcsUrl: String, revision: String): Option[VCSInfo] = {
    log.info("url:%s revision:%s" format(ciVcsUrl, revision))
    ciVcsUrl match {
      case GuGitFile(repo) => Some(GuGit(ciVcsUrl, revision, repo))
      case GitHubProtocol(repo) => Some(GitHub(ciVcsUrl, revision, repo))
      case GitHubUser(repo) => Some(GitHub(ciVcsUrl, revision, repo))
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
}
