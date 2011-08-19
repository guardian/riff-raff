package com.gu.deploy2



object Resolver {
  def parse(input: JsonInputFile): Install = {
    val roles = input.roles map Role
    val packages = input.packages mapValues { Package.parse(_, roles) }

    Install(roles, packages)
  }

}