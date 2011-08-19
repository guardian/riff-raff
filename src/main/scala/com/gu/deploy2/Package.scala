package com.gu.deploy2

trait PackageImpl

case class JettyWebappPackage() extends PackageImpl {

}

case class Package(roles: List[Role], impl: PackageImpl) {

}

object Package {
  def parse(jsonPackage: JsonPackage, allRoles: List[Role]) = {

    // TODO: obviously the two .get's here are to be replaced with proper error handling
    Package(
      jsonPackage.defaultRoles map { roleName => allRoles.find(_.name == roleName).get },
      packages.get(jsonPackage.`type`).get
    )
  }

  lazy val packages = Map(
    "jetty-webapp" -> new JettyWebappPackage()
  )
  def getImpl(pkgType: String) = packages(pkgType)
}