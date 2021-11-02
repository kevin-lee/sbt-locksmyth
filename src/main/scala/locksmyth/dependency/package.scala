package locksmyth

import CommonDef._
import sbt.Configuration

/** @author Kevin Lee
  */
package object dependency {

  def isScalaLibraryId(id: ModuleId): Boolean = id.organisation === "org.scala-lang" && id.name === "scala-library"
  def isScalaLibrary(m: Module): Boolean      = isScalaLibraryId(m.id)

  def dependsOnScalaLibrary(m: Module, dependencyMap: Map[ModuleId, Seq[Module]]): Boolean =
    dependencyMap.get(m.id).exists(_.exists(isScalaLibrary))

  def addScalaLibraryAnnotation(m: Module, dependencyMap: Map[ModuleId, Seq[Module]]): Module = {
    if (dependsOnScalaLibrary(m, dependencyMap))
      m.copy(extraInfo = m.extraInfo + " [S]")
    else
      m
  }

  def ignoreScalaLibrary(
    graphs: Seq[(Configuration, ModuleGraph)]
  ): Seq[(Configuration, ModuleGraph)] = {
    def ignoreScalaLibrary0(graph: ModuleGraph): ModuleGraph = {
      val dependencyMap = graph.dependencyMap
      val newNodes      = graph.nodes.map(addScalaLibraryAnnotation(_, dependencyMap)).filterNot(isScalaLibrary)
      val newEdges      = graph.edges.filterNot(e => isScalaLibraryId(e.module))
      ModuleGraph(newNodes, newEdges)
    }
    graphs.map(graph => (graph._1, ignoreScalaLibrary0(graph._2)))
  }
}
