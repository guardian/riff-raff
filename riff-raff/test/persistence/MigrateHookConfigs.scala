package persistence

object MigrateHookConfigs {
  def main(args: Array[String]): Unit = {
    MongoDatastore.buildDatastore().get.getPostDeployHookList.foreach(
      HookConfigRepository.setPostDeployHook
    )
  }
}
