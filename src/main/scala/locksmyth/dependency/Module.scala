package locksmyth.dependency

import just.semver.SemVer
import locksmyth.CommonDef._
import locksmyth.dependency.ConflictableModuleId.UsageStatus
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

  final case class ModuleIdNoConflictId(moduleId: ModuleId, usageStatus: UsageStatus) extends ConflictableModuleId

  final case class ModuleIdConflictId(
    moduleId: ModuleId,
    usageStatus: UsageStatus,
    callers: Vector[ModuleId]
  ) extends ConflictableModuleId

  def noConflict(moduleId: ModuleId, usageStatus: UsageStatus): ConflictableModuleId =
    ModuleIdNoConflictId(moduleId, usageStatus)

  def conflict(moduleId: ModuleId, usageStatus: UsageStatus, callers: Vector[ModuleId]): ConflictableModuleId =
    ModuleIdConflictId(moduleId, usageStatus, callers)

  implicit final class ConflictableModuleIdOps(private val conflictableModuleId: ConflictableModuleId) extends AnyVal {

    def isInUse: Boolean = !isEvicted

    def isEvicted: Boolean = conflictableModuleId match {
      case ConflictableModuleId.ModuleIdNoConflictId(_, UsageStatus.Evicted)  => true
      case ConflictableModuleId.ModuleIdNoConflictId(_, UsageStatus.InUse)    => false
      case ConflictableModuleId.ModuleIdConflictId(_, UsageStatus.Evicted, _) => true
      case ConflictableModuleId.ModuleIdConflictId(_, UsageStatus.InUse, _)   => false
    }

  }

  def toModuleId(conflictableModuleId: ConflictableModuleId): ModuleId = conflictableModuleId match {
    case ModuleIdNoConflictId(moduleId, _)  => moduleId
    case ModuleIdConflictId(moduleId, _, _) => moduleId
  }

  def sortByModuleId(modules: Vector[ConflictableModuleId]): Vector[ConflictableModuleId] =
    modules.sortWith((a, b) => ModuleId.lessThan(toModuleId(a), toModuleId(b)))

  def renderWithConfig(conflictableModuleId: ConflictableModuleId, config: Configuration): String =
    conflictableModuleId match {
      case ModuleIdConflictId(moduleId, evicted, callers) =>
        s"""# Used by ${callers.map(_.idString).mkString("[", ", ", "]")} (${evicted.render})
           |* ${ModuleId.renderWithConfig(moduleId, config)}""".stripMargin

      case ModuleIdNoConflictId(moduleId, _) =>
        ModuleId.renderWithConfig(moduleId, config)
    }

  def renderNonEvictedWithConfig(conflictableModuleId: ConflictableModuleId, config: Configuration): String =
    conflictableModuleId match {
      case ModuleIdConflictId(moduleId, UsageStatus.InUse, _) =>
        ModuleId.renderWithConfig(moduleId, config)
      case ModuleIdConflictId(_, UsageStatus.Evicted, _)      =>
        ""
      case ModuleIdNoConflictId(moduleId, UsageStatus.InUse)  =>
        ModuleId.renderWithConfig(moduleId, config)
      case ModuleIdNoConflictId(_, UsageStatus.Evicted)       =>
        ""
    }

  sealed trait UsageStatus
  object UsageStatus {
    case object InUse   extends UsageStatus
    case object Evicted extends UsageStatus

    def inUse: UsageStatus   = InUse
    def evicted: UsageStatus = Evicted

    def evictedIfTrue(evicted: Boolean): UsageStatus = if (evicted) UsageStatus.evicted else UsageStatus.inUse

    implicit final class UsageStatusOps(private val usageStatus: UsageStatus) extends AnyVal {
      def render: String = usageStatus match {
        case UsageStatus.InUse   => "use"
        case UsageStatus.Evicted => "evicted"
      }
    }
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

  implicit final class UniqueDependenciesOps(private val uniqueDependencies: UniqueDependencies) extends AnyVal {
    def filter(f: ConflictableModuleId => Boolean): UniqueDependencies =
      UniqueDependencies(
        compile = uniqueDependencies.compile.filter(f),
        test = uniqueDependencies.test.filter(f),
        provided = uniqueDependencies.provided.filter(f),
      )
  }

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
  ): Vector[(ModuleId, Module)] =
    configDependenciesList
      .find(_.config === config)
      .map(configDependencies =>
        configDependencies
          .dependencies
          .filterNot { case (moduleId, _) => moduleId.name === projectName }
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
      providedDeps.filterNot {
        case (providedModuleId, _) =>
          compileDeps.exists { case (compileModuleId, _) => compileModuleId.keyString === providedModuleId.keyString }
      }
    val filteredTestDeps     =
      testDeps.filterNot {
        case (testModuleId, _) =>
          filteredProvidedDeps.exists {
            case (filteredProvidedModuleId, _) =>
              filteredProvidedModuleId.keyString === testModuleId.keyString
          } || compileDeps.exists {
            case (compileModuleId, _) =>
              compileModuleId.keyString === testModuleId.keyString
          }
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
    compileDeps: Vector[(ModuleId, Module)]
  ): Vector[ConflictableModuleId] = {
    val compileDepsConflicts = filterConflicts(compileDeps)
    for {
      (depModuleId, depModule) <- compileDeps
      moduleIdPlus =
        if (compileDepsConflicts.exists { case (orgName, _) => orgName === UniqueDependencies.orgAndName(depModuleId) })
          ConflictableModuleId.conflict(
            depModuleId,
            UsageStatus.evictedIfTrue(depModule.isEvicted),
            compileEdges.filter(edge => edge.module === depModuleId).map(edge => edge.caller)
          )
        else
          ConflictableModuleId.noConflict(depModuleId, UsageStatus.evictedIfTrue(depModule.isEvicted))
    } yield moduleIdPlus
  }

  private def filterConflicts(dependencies: Vector[(ModuleId, Module)]): Map[String, Vector[(ModuleId, Module)]] =
    dependencies
      .groupBy { case (moduleId, _) => UniqueDependencies.orgAndName(moduleId) }
      .filter { case (_, moduleIds) => moduleIds.length > 1 }

  def renderElems(uniqueDependencies: UniqueDependencies): Vector[String] =
    uniqueDependencies.compile.map(moduleId => ConflictableModuleId.renderWithConfig(moduleId, Compile)) ++
      uniqueDependencies.test.map(moduleId => ConflictableModuleId.renderWithConfig(moduleId, Test)) ++
      uniqueDependencies.provided.map(moduleId => ConflictableModuleId.renderWithConfig(moduleId, Provided))

  def renderNonEvictedElems(uniqueDependencies: UniqueDependencies): Vector[String] =
    (
      uniqueDependencies.compile.map(moduleId => ConflictableModuleId.renderNonEvictedWithConfig(moduleId, Compile)) ++
        uniqueDependencies.test.map(moduleId => ConflictableModuleId.renderNonEvictedWithConfig(moduleId, Test)) ++
        uniqueDependencies.provided.map(moduleId => ConflictableModuleId.renderNonEvictedWithConfig(moduleId, Provided))
    ).filter(_.nonEmpty)

}
