package com.gu.deploy2

import net.liftweb.util.StringHelpers._

case class Host(name: String)

trait Task {
  def execute()
}

case class CopyFileTask(files:Seq[(String,String)]) extends Task {
  def execute() {

  }
}

// maybe this is just a function?
trait Action {
  def resolve(host: Host): Seq[Task]
}

case class Role(name: String)

// Hmm. I actually want dependsOn to be a seq of recipes, but
// this creates a circular dep when parsing. So for now, it's a
// list of recipe names
case class Recipe(
  actions: Seq[Action],
  dependsOn: Seq[String]
)


object Recipe {
  def parse(jsonRecipe: JsonRecipe, availablePackages: Map[String, Package]) = {
    def parseAction(actionString: String) = {
      actionString.split("\\.") match {
        case Array(pkgName, actionName) =>
          val pkg = availablePackages.get(pkgName).getOrElse(sys.error("Unknown package in action: " + actionString))
          pkg.action(actionName)

        case _ => sys.error("Badly formed action name: " + actionString)
      }
    }

    Recipe(
      actions = jsonRecipe.actions map parseAction,
      dependsOn = jsonRecipe.depends)
  }
}

case class Install(
  packages: Map[String, Package],
  recipes: Map[String, Recipe]
) {
  lazy val roles = packages.values.flatMap(_.roles).toSet
}