package magenta

import tasks.Task


object Resolver {
  def resolve(project: Project, recipeName: String, deployinfo: List[Host]): List[Task] = {
    val recipe = project.recipes(recipeName)

    recipe.dependsOn.flatMap { resolve(project, _, deployinfo) } ++ {
      for {
        host <- deployinfo
        action <- recipe.actions.filterNot(action => (action.apps & host.apps).isEmpty)
        tasks <- action.resolve(host)
      } yield {
        tasks
      }
    }
  }
  
  def possibleApps(project: Project, recipeName: String): String = {
    val recipe = project.recipes(recipeName)
    val appNames = for {
      action <- recipe.actions
      app <- action.apps
    } yield app.name
    appNames.mkString(", ")
  }

}




