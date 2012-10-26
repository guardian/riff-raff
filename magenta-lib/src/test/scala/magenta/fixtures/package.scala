package magenta

package object fixtures {
  def stubPackageType(perAppActionNames: Seq[String], perHostActionNames: Seq[String],
                      apps: Set[App]) = StubPackageType(
    perAppActions = {
      case name if (perAppActionNames.contains(name)) => params => List(StubTask(name + " per app task"))
    },
    perHostActions = {
      case name if (perHostActionNames.contains(name))=> host =>
        List(StubTask(name + " per host task on " + host.name, Some(host)))
    },
    pkg = StubPackage().copy(pkgApps = apps)
  )
}
