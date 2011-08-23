package com.gu.deploy2

import net.liftweb.util.StringHelpers._

case class Host(name: String, roles: Set[Role] = Set.empty) {
  def role(name: String) = this.copy(roles = roles + Role(name))
  def role(role: Role) = this.copy(roles = roles + role)
}

/*
 An action represents a step within a recipe. It isn't executable
 until it's reolved against a particular host.
 */
trait Action {
  def resolve(host: Host): List[Task]
  def roles: Set[Role]
  def description: String
}

case class Role(name: String)

case class Recipe(
  name: String,
  actions: List[Action] = Nil,
  dependsOn: List[String] = Nil
) {
  lazy val roles = actions.flatMap(_.roles).toSet
}



case class Install(
  packages: Map[String, Package],
  recipes: Map[String, Recipe]
) {
  lazy val roles = packages.values.flatMap(_.roles).toSet
}