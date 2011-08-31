package com.gu.deploy

import tasks.Task

case class Host(
    name: String,
    roles: Set[Role] = Set.empty,
    stage: String = "NO_STAGE",
    connectAs: Option[String] = None)
{
  def role(name: String) = this.copy(roles = roles + Role(name))
  def role(role: Role) = this.copy(roles = roles + role)

  def as(user: String) = this.copy(connectAs = Some(user))

  // this allows "resin" @: Host("some-host")
  def @:(user: String) = as(user)

  lazy val connectStr = (connectAs map { _ + "@" } getOrElse "") + name
}


/*
 An action represents a step within a recipe. It isn't executable
 until it's resolved against a particular host.
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



case class Project(
  packages: Map[String, Package] = Map.empty,
  recipes: Map[String, Recipe] = Map.empty
) {
  lazy val roles = packages.values.flatMap(_.roles).toSet
}