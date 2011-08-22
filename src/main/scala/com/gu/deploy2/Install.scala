package com.gu.deploy2

import net.liftweb.util.StringHelpers._

case class Host(name: String)

trait Task {
  def execute()
}

case class CopyFileTask(source:String,dest:String) extends Task {
  def execute() {

  }
}

/*
 An action represents a step within a recipe. It isn't executable
 until it's reolved against a particular host.
 */
trait Action {
  def resolve(host: Host): Seq[Task]
  def roles: Seq[Role]
  def description: String
}

case class Role(name: String)

case class Recipe(
  name: String,
  actions: Seq[Action],
  dependsOn: Seq[String]
)



case class Install(
  packages: Map[String, Package],
  recipes: Map[String, Recipe]
) {
  lazy val roles = packages.values.flatMap(_.roles).toSet
}