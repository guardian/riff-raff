package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.input._

object DeploymentFilter {
  type Filter = Deployment => Option[Deployment]

  def filter(deployments: List[Deployment], filter: Filter): List[Deployment] = {
    deployments.flatMap(filter(_))
  }

  def create(userFilter: UserDeploymentFilter): Filter =
    userFilter match {
      case NoFilter => Identity
      case DeploymentIdsFilter(ids) => Ids(ids)
    }

  /** A filter function that returns all deployments unmodified */
  val Identity: Filter = deployment => Some(deployment)

  /** Returns a function that modifies deployments to apply to only the given stack and region.
    * If the stack and region do not appear in the deployment then None is returned.
    * If the stack and region do appear then a deployment will be returned with only that region and stack.
    */
  def StackAndRegion(stack: String, region: String): Filter = { deployment =>
    if (deployment.stacks.exists(stack ==) && deployment.regions.exists(region ==))
      Some(deployment.copy(stacks = NEL.of(stack), regions = NEL.of(region)))
    else
      None
  }
  /** This filters a deployment against the list of selected IDs.
    * If none of the Ids match the deployment then None will be returned.
    * If there are IDs that match all the actions, regions and stacks then the deployment will be returned unmodified.
    * If the deployment partially matches then a modified deployment will be returned with that subset of actions,
    * regions and stacks.
    * */
  def Ids(ids: List[DeploymentId]): Filter = { deployment =>
    def matchId(id: DeploymentId): Boolean = {
      deployment.name == id.name && deployment.actions.toList.flatten.contains(id.action) &&
        deployment.regions.toList.contains(id.region) && deployment.stacks.toList.contains(id.stack)
    }
    val matchingIds = ids filter matchId
    if (matchingIds.nonEmpty) {
      Some(deployment.copy(
        actions = deployment.actions.map(_.filter(a => matchingIds.exists(_.action == a))),
        regions = NEL.fromListUnsafe(deployment.regions.filter(r => matchingIds.exists(_.region == r))),
        stacks = NEL.fromListUnsafe(deployment.stacks.filter(s => matchingIds.exists(_.stack == s)))
      ))
    } else None
  }
}
