package com.gu.deploy2



object Resolver {
  def resolve(recipe: Recipe, deployinfo: List[Host]): List[Task] = {
    val hosts = deployinfo.filter(host => !(recipe.roles & host.roles).isEmpty)

    println("hosts = " + hosts)

    for {
      host <- hosts
      action <- recipe.actions
      tasks <- action.resolve(host)
    } yield {
      tasks
    }
  }

}




