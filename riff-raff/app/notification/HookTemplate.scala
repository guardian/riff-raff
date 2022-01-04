package notification

import java.net.URLEncoder

import org.parboiled2._
import org.parboiled2.CharPredicate._
import persistence.{DeployRecordDocument}

class HookTemplate(val input: ParserInput, record: DeployRecordDocument, urlEncode: Boolean = false) extends Parser {
  def Template = rule{ NonToken ~ (zeroOrMore(SubstitutionToken ~ NonToken ~> (_ + _)) ~> (_.mkString)) ~> (_ + _) }

  def NonToken = rule { capture(zeroOrMore(!SubstitutionToken ~ ANY))  }

  def SubstitutionToken = rule { "%deploy." ~ Param ~ '%' }

  def Param = rule { SimpleParam | Tag }

  def SimpleParam = {
    val params = HookTemplate.paramSubstitutions.view.mapValues(f => encode(f(record))).toMap
    rule { params }
  }

  def Tag = rule { "tag." ~ capture( oneOrMore(Alpha | '-' | '_')) ~> (tagName =>
    encode(record.parameters.tags.getOrElse(tagName, ""))
  )}

  def encode(p: String) = if (urlEncode) URLEncoder.encode(p, "utf-8") else p
}

object HookTemplate {
  val paramSubstitutions = Map[String, DeployRecordDocument => String](
    ("build", _.parameters.buildId),
    ("projectName", _.parameters.projectName),
    ("project", _.parameters.projectName),
    ("stage", _.parameters.stage),
    ("deployer", _.parameters.deployer),
    ("uuid", _.uuid.toString)
  )
}
