package locksmyth.dependency

import just.semver.SemVer
import locksmyth.CommonDef._
import locksmyth.util.SbtUtils
import sbt.{Compile, Configuration, Provided, Test}

import java.io.File
import scala.collection.mutable

/** @author Kevin Lee
  */
final case class Module(
  id: ModuleId,
  license: Option[String],
  extraInfo: String,
  evictedByVersion: Option[String],
  jarFile: Option[File],
  error: Option[String]
) {
  def hasError: Boolean  = error.isDefined
  def isUsed: Boolean    = !isEvicted
  def isEvicted: Boolean = evictedByVersion.isDefined
}

object Module {
  def withOnlyModuleId(moduleId: ModuleId): Module = Module(moduleId, None, "", None, None, None)
}

sealed trait ConflictableModuleId

object ConflictableModuleId {

  final case class ModuleIdNoConflictId(moduleId: ModuleId) extends ConflictableModuleId

  final case class ModuleIdConflictId(
    moduleId: ModuleId,
    callers: Vector[ModuleId]
  ) extends ConflictableModuleId

  def noConflict(moduleId: ModuleId): ConflictableModuleId =
    ModuleIdNoConflictId(moduleId)

  def conflict(moduleId: ModuleId, callers: Vector[ModuleId]): ConflictableModuleId =
    ModuleIdConflictId(moduleId, callers)

  def toModuleId(conflictableModuleId: ConflictableModuleId): ModuleId = conflictableModuleId match {
    case ModuleIdNoConflictId(moduleId)  => moduleId
    case ModuleIdConflictId(moduleId, _) => moduleId
  }

  def sortByModuleId(modules: Vector[ConflictableModuleId]): Vector[ConflictableModuleId] =
    modules.sortWith((a, b) => ModuleId.lessThan(toModuleId(a), toModuleId(b)))

  def renderWithConfig(conflictableModuleId: ConflictableModuleId, config: Configuration): String =
    conflictableModuleId match {
      case ModuleIdConflictId(moduleId, callers) =>
        s"""# Used by ${callers.map(_.idString).mkString("[", ", ", "]")}
           |* ${ModuleId.renderWithConfig(moduleId, config)}""".stripMargin

      case ModuleIdNoConflictId(moduleId) =>
        ModuleId.renderWithConfig(moduleId, config)
    }
}

final case class Edge(caller: ModuleId, module: ModuleId)

final case class ModuleGraph(nodes: Seq[Module], edges: Seq[Edge]) {
  lazy val modules: Map[ModuleId, Module] =
    this.nodes.map(n => (n.id, n)).toMap
}

object ModuleGraph {
  def empty: ModuleGraph = ModuleGraph(Vector.empty, Vector.empty)

  implicit final class ModuleGraphOps(private val moduleGraph: ModuleGraph) extends AnyVal {

    def module(id: ModuleId): Module = moduleGraph.modules(id)

    def dependencyMap: Map[ModuleId, Seq[Module]] =
      createMap(edge => (edge.caller, edge.module))

    def createMap(bindingFor: Edge => (ModuleId, ModuleId)): Map[ModuleId, Seq[Module]] = {
      val m = new mutable.HashMap[ModuleId, mutable.Set[Module]] with mutable.MultiMap[ModuleId, Module]
      moduleGraph.edges.foreach { entry =>
        val (f, t) = bindingFor(entry)
        m.addBinding(f, module(t))
      }
      m.toMap
        .mapValues(
          _.toVector.sortWith((a, b) => ModuleId.lessThan(a.id, b.id))
        )
        .withDefaultValue(Vector.empty)
    }

    def roots: Seq[Module] =
      moduleGraph
        .nodes
        .filter(n => !moduleGraph.edges.exists(_.module === n.id))
        .sortWith((a, b) => ModuleId.lessThan(a.id, b.id))
  }
}

final case class ConfigDependencies(config: Configuration, dependencies: Map[ModuleId, Module], edges: Vector[Edge])
object ConfigDependencies {
  def edges(configDependencies: Vector[ConfigDependencies], config: Configuration): Vector[Edge] =
    configDependencies.find(_.config === config).fold(Vector.empty[Edge])(_.edges)
}

final case class ProjectName(projectName: String) extends AnyVal

final case class ProjectDependencies(projectName: ProjectName, configToDependencies: Vector[ConfigDependencies])

final case class UniqueDependencies(
  compile: Vector[ConflictableModuleId],
  test: Vector[ConflictableModuleId],
  provided: Vector[ConflictableModuleId]
)

object UniqueDependencies {

  def projectNameWithScalaVersion(projectName: ProjectName, scalaVersion: SemVer): String = {
    val scalaBinaryVersion = SbtUtils.getScalaBinaryVersion(scalaVersion)
    s"${projectName.projectName}_$scalaBinaryVersion"
  }

  def orgAndName(moduleId: ModuleId): String =
    s"${moduleId.organisation}:${moduleId.name}"

  /** Get all ModuleIds for the given config after removing this project
    * as the list of dependencies has the project itself.
    */
  def moduleIdsForConfig(
    config: Configuration,
    projectName: String,
    configDependenciesList: Vector[ConfigDependencies]
  ): Vector[ModuleId] =
    configDependenciesList
      .find(_.config === config)
      .map(configDependencies =>
        configDependencies
          .dependencies
          .keys
          .filterNot(moduleId => moduleId.name === projectName)
          .toVector
      )
      .getOrElse(Vector.empty)

  /** Remove duplicate dependencies from ConfigDependencies for Compile, Test and Provided configs as well as this project module.
    * Each config may have duplicate dependencies and Compile takes the highest precedence followed by Provided. Test has the lowest of all three.
    *
    * First, it removes the project itself from the dependencies List. The dependencies List contains the project itself having those dependencies so it has to be removed from the List.
    *
    * After that this method removes any dependencies in Compile from Provided and Test and also remove any dependencies in Provided from Test.
    * (i.e. Provided - Compile, Test - Compile, Test - Provided)
    *
    * @param projectName the project containing the dependencies.
    * @param configDependencies List of ConfigDependencies for the project with the given projectName.
    * @return List of Configuration to ModuleId with only unique ModuleIds per Configuration. This List has the ModuleId with the given projectName removed as well.
    */
  def toUniqueDependencies(projectName: String, configDependencies: Vector[ConfigDependencies]): UniqueDependencies = {
    val compileDeps  = moduleIdsForConfig(Compile, projectName, configDependencies)
    val testDeps     = moduleIdsForConfig(Test, projectName, configDependencies)
    val providedDeps = moduleIdsForConfig(Provided, projectName, configDependencies)

    val filteredProvidedDeps =
      providedDeps.filterNot(moduleId => compileDeps.exists(_.keyString === moduleId.keyString))
    val filteredTestDeps     =
      testDeps.filterNot { moduleId =>
        filteredProvidedDeps.exists(_.keyString === moduleId.keyString) ||
        compileDeps.exists(_.keyString === moduleId.keyString)
      }

    val compileModuleIds =
      moduleIdsWithConflictInfo(ConfigDependencies.edges(configDependencies, Compile), compileDeps)

    val testModuleIds =
      moduleIdsWithConflictInfo(ConfigDependencies.edges(configDependencies, Test), filteredTestDeps)

    val providedModuleIds =
      moduleIdsWithConflictInfo(ConfigDependencies.edges(configDependencies, Test), filteredProvidedDeps)

    UniqueDependencies(
      compile = ConflictableModuleId.sortByModuleId(compileModuleIds),
      test = ConflictableModuleId.sortByModuleId(testModuleIds),
      provided = ConflictableModuleId.sortByModuleId(providedModuleIds)
    )
  }

  private def moduleIdsWithConflictInfo(
    compileEdges: Vector[Edge],
    compileDeps: Vector[ModuleId]
  ): Vector[ConflictableModuleId] = {
    val compileDepsConflicts = filterConflicts(compileDeps)
    for {
      dep <- compileDeps
      moduleIdPlus =
        if (compileDepsConflicts.exists { case (orgName, _) => orgName === UniqueDependencies.orgAndName(dep) })
          ConflictableModuleId.conflict(dep, compileEdges.filter(edge => edge.module === dep).map(edge => edge.caller))
        else
          ConflictableModuleId.noConflict(dep)
    } yield moduleIdPlus
  }

  private def filterConflicts(dependencies: Vector[ModuleId]): Map[String, Vector[ModuleId]] =
    dependencies
      .groupBy(moduleId => UniqueDependencies.orgAndName(moduleId))
      .filter { case (_, moduleIds) => moduleIds.length > 1 }

  def renderElems(uniqueDependencies: UniqueDependencies): Vector[String] = {
    uniqueDependencies.compile.map(moduleId => ConflictableModuleId.renderWithConfig(moduleId, Compile)) ++
      uniqueDependencies.test.map(moduleId => ConflictableModuleId.renderWithConfig(moduleId, Test)) ++
      uniqueDependencies.provided.map(moduleId => ConflictableModuleId.renderWithConfig(moduleId, Provided))
  }

}
