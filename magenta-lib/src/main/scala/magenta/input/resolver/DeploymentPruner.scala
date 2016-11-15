package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.input._

object DeploymentPruner {
  /* Type that selects all, part or nothing of a given Deployment */
  type Pruner = Deployment => Option[Deployment]

  def prune(deployments: List[Deployment], pruner: Pruner): List[Deployment] = {
    deployments.flatMap(pruner(_))
  }

  def create(userSelector: DeploymentSelector): Pruner =
    userSelector match {
      case All => Identity
      case DeploymentKeysSelector(deploymentKeys) => Keys(deploymentKeys)
    }

  /** A pruning function that returns all deployments unmodified */
  val Identity: Pruner = deployment => Some(deployment)

  /** Returns a function that modifies deployments to apply to only the given stack and region.
    * If the stack and region do not appear in the deployment then None is returned.
    * If the stack and region do appear then a deployment will be returned with only that region and stack.
    */
  def StackAndRegion(stack: String, region: String): Pruner = { deployment =>
    if (deployment.stacks.exists(stack ==) && deployment.regions.exists(region ==))
      Some(deployment.copy(stacks = NEL.of(stack), regions = NEL.of(region)))
    else
      None
  }
  /** This prunes a deployment against the list of selected keys.
    * If none of the keys match the deployment then None will be returned.
    * If there are keys that match all the actions, regions and stacks then the deployment will be returned unmodified.
    * If the deployment partially matches then a modified deployment will be returned with that subset of actions,
    * regions and stacks.
    * */
  def Keys(keys: List[DeploymentKey]): Pruner = { deployment =>
    def matchKey(key: DeploymentKey): Boolean = {
      deployment.name == key.name && deployment.actions.toList.flatten.contains(key.action) &&
        deployment.regions.toList.contains(key.region) && deployment.stacks.toList.contains(key.stack)
    }
    val matchingKeys = keys filter matchKey
    if (matchingKeys.nonEmpty) {
      Some(deployment.copy(
        actions = deployment.actions.map(_.filter(a => matchingKeys.exists(_.action == a))),
        regions = NEL.fromListUnsafe(deployment.regions.filter(r => matchingKeys.exists(_.region == r))),
        stacks = NEL.fromListUnsafe(deployment.stacks.filter(s => matchingKeys.exists(_.stack == s)))
      ))
    } else None
  }
}
