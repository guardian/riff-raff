package com.gu.deploy2



object Resolver {
  def resolve(project: Project, recipeName: String, deployinfo: List[Host]): List[Task] = {
    val recipe = project.recipes(recipeName)

    recipe.dependsOn.flatMap { resolve(project, _, deployinfo) } ++ {
      for {
        host <- deployinfo.filterNot(host => (recipe.roles & host.roles).isEmpty)
        action <- recipe.actions
        tasks <- action.resolve(host)
      } yield {
        tasks
      }
    }
  }

}




