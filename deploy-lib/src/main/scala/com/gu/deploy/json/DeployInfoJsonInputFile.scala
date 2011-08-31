package com.gu.deploy.json

import net.liftweb.json._
import io.Source
import java.io.File
import com.gu.deploy.{Role, Host}

case class DeployInfoJsonInputFile(
  hosts: List[DeployInfoHost]
)


case class DeployInfoHost(
  hostname: String,
  role: String,
  group: String,
  stage: String
)


object DeployInfoJsonReader {
  private implicit val formats = DefaultFormats

  def parse(f: File):  List[Host] = parse(Source.fromFile(f).mkString)

  def parse(inputFile: DeployInfoJsonInputFile): List[Host] = {
    inputFile.hosts map { host => Host(host.hostname, Set(Role(host.role)), host.stage) }
  }

  def parse(s: String): List[Host] = {
    parse(Extraction.extract[DeployInfoJsonInputFile](JsonParser.parse(s)))
  }

}


