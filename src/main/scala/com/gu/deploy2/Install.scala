package com.gu.deploy2


case class Role(name: String)

case class Install(
  roles: List[Role],
  packages: Map[String, Package]
) {

}