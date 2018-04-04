package magenta

import java.util.UUID

import magenta.graph.{DeploymentGraph, DeploymentTasks, Graph}

case class DeployContext(uuid: UUID, parameters: DeployParameters, tasks: Graph[DeploymentTasks])

case class DeployStoppedException(message:String) extends Exception(message)
