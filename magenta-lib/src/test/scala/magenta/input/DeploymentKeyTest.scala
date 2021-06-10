package magenta.input

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeploymentKeyTest extends AnyFlatSpec with Matchers {
  "DeploymentKey" should "serialise and deserialise a key to and from a string" in {
    val key = DeploymentKey("name", "action", "stack", "region")
    val string = DeploymentKey.asString(key)
    string shouldBe "name*action*stack*region"
    DeploymentKey.fromString(string) shouldBe Some(key)
  }

  it should "serialise and deserialise a list of keys to and from a string" in {
    val keys = List(
      DeploymentKey("name", "action1", "stack1", "region1"),
      DeploymentKey("name", "action2", "stack2", "region2"),
      DeploymentKey("name", "action3", "stack3", "region3")
    )
    val string = DeploymentKey.asString(keys)
    string shouldBe "name*action1*stack1*region1!name*action2*stack2*region2!name*action3*stack3*region3"
    DeploymentKey.fromStringToList(string) shouldBe keys
  }
}
