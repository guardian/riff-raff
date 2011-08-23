package com.gu.deploy2



object Resolver {
  def resolve(recipe: Recipe, deployinfo: List[Host]): List[Task] = {
    for {
      host <- deployinfo.filterNot(host => (recipe.roles & host.roles).isEmpty)
      action <- recipe.actions
      tasks <- action.resolve(host)
    } yield {
      tasks
    }
  }

}




