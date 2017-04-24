package magenta

import org.scalatest.{FlatSpec, Matchers}

class HostListTests extends FlatSpec with Matchers {
  import magenta.HostList._

  it should "provide an alphabetical list of all hosts and supported apps" in {
    val hostList = List(Host("z.z.z.z", Set(App("z"))), Host("x.x.x.x", Set(App("x"))))
    hostList.dump should be(" x.x.x.x: App(x)\n z.z.z.z: App(z)")
  }
}
