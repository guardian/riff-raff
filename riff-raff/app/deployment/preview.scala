package deployment

import magenta._
import persistence.Persistence
import magenta.teamcity.Artifact.build2download
import java.io.File
import io.Source
import magenta.json.JsonReader
import controllers.routes
import magenta.DeployParameters
import magenta.Project
import magenta.Build
import tasks.{ Task => MagentaTask }

object Preview {
  /**
   * Get the project for the build for preview purposes.  The project returned will not have a valid artifactDir so
   * cannot be used for an actual deploy.
   * This is cached in the database so future previews are faster.
   */
  def getProject(build: Build): Project = {
    val json = Persistence.store.getDeployJson(build).getOrElse {
      build.withDownload { artifactDir =>
        val json = Source.fromFile(new File(artifactDir, "deploy.json")).getLines().mkString
        Persistence.store.writeDeployJson(build, json)
        json
      }
    }
    JsonReader.parse(json, new File(System.getProperty("java.io.tmpdir")))
  }

  def apply(parameters: DeployParameters): Preview = {
    val project = Preview.getProject(parameters.build)
    Preview(project, parameters)
  }
}

case class Preview(project: Project, parameters: DeployParameters) {
  lazy val deployInfo = DeployInfoManager.deployInfo
  lazy val recipes = recipeTasks.map(_.recipe)
  lazy val allRecipes = project.recipes.values.map(_.name).toList.sorted
  lazy val dependantRecipes = recipes.filterNot(_ == recipe)
  def isDependantRecipe(r: String) = r != recipe && recipes.contains(r)
  def dependsOn(r: String) = project.recipes(r).dependsOn

  lazy val recipeTasks = Resolver.resolveDetail(project, deployInfo, parameters)
  lazy val tasks = recipeTasks.flatMap(_.tasks)

  def taskHosts(taskList:List[MagentaTask]) = taskList.flatMap(_.taskHost).filter(deployInfo.hosts.contains).map(_.name).distinct

  lazy val hosts = taskHosts(tasks)
  lazy val allHosts = {
    val allTasks = Resolver.resolve(project, deployInfo, parameters.copy(recipe = RecipeName(recipe), hostList=Nil)).distinct
    taskHosts(allTasks)
  }
  lazy val allPossibleHosts = {
    val allTasks = allRecipes.flatMap(recipe => Resolver.resolve(project, deployInfo, parameters.copy(recipe = RecipeName(recipe), hostList=Nil))).distinct
    taskHosts(allTasks)
  }

  val projectName = parameters.build.projectName
  val build = parameters.build.id
  val stage = parameters.stage.name
  val recipe = parameters.recipe.name
  val hostList = parameters.hostList.mkString(",")

  def withRecipe(newRecipe: String) = routes.Deployment.preview(projectName, build, stage, newRecipe, "")
}