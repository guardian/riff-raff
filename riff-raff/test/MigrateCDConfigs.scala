import persistence.{ContinuousDeploymentConfigRepository, Persistence}

object MigrateCDConfigs {
  def main(args: Array[String]) {
    Persistence.store.getContinuousDeploymentList().foreach(ContinuousDeploymentConfigRepository.setContinuousDeployment)
  }
}
