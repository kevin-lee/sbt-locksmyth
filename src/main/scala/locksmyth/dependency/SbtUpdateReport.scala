package locksmyth.dependency

import locksmyth.CommonDef._
import sbt._

/** @author Kevin Lee
  */
object SbtUpdateReport {

  def fromConfigurationReport(report: ConfigurationReport, rootInfo: sbt.ModuleID): ModuleGraph = {
    def moduleId(sbtId: sbt.ModuleID): ModuleId =
      ModuleId(sbtId.organization, sbtId.name, sbtId.revision)

    def moduleEdges(orgArt: OrganizationArtifactReport): Seq[(Module, Seq[Edge])] = {
      val chosenVersion = orgArt.modules.find(!_.evicted).map(_.module.revision)
      orgArt.modules.map(moduleEdge(chosenVersion))
    }

    def moduleEdge(chosenVersion: Option[String])(report: ModuleReport): (Module, Seq[Edge]) = {
      val evictedByVersion = if (report.evicted) chosenVersion else None

      val jarFile =
        report
          .artifacts
          .find {
            case (artifact, _) => artifact.`type` === "jar"
          }
          .orElse(
            report.artifacts.find {
              case (artifact, _) => artifact.extension === "jar"
            }
          )
          .map {
            case (_, file) => file
          }
      (
        Module(
          id = moduleId(report.module),
          license = report.licenses.headOption.map(_._1),
          extraInfo = "",
          evictedByVersion = evictedByVersion,
          jarFile = jarFile,
          error = report.problem
        ),
        report.callers.map(caller => Edge(moduleId(caller.caller), moduleId(report.module)))
      )
    }

    val (nodes, edges) = report.details.flatMap(moduleEdges).unzip
    val root           = Module.withOnlyModuleId(moduleId(rootInfo))

    ModuleGraph(root +: nodes, edges.flatten)
  }
}
