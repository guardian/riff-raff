package com.gu.deploy

case class Host(hostname: String) {

  def colo = {
    val ColoRegex = """^.*?\.(.*?)\..*?$""".r

    hostname match {
      case ColoRegex(colo) => Some(colo)
      case _ => None
    }
  }

}