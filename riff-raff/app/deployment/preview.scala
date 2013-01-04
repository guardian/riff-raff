package deployment

import magenta.{Build, Project}
import persistence.Persistence
import magenta.teamcity.Artifact.build2download
import java.io.File
import io.Source
import magenta.json.JsonReader

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
}
