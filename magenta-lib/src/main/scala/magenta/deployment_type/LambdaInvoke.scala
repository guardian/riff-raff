package magenta.deployment_type

import magenta.tasks.InvokeLambda
import magenta.KeyRing

object LambdaInvoke extends LambdaInvoke

trait LambdaInvoke extends DeploymentType with BucketParameters {
  val name = "aws-invoke-lambda"

  override def documentation: String = "Invokes Lambda"

  override def defaultActions: List[Action] = List(Action("invoke","Do nothing"){
    (pkg, resources, target) => {
      List(InvokeLambda(artifactsPath = pkg.s3Package)(keyRing = resources.assembleKeyring(target, pkg)))
    }
  })
}

