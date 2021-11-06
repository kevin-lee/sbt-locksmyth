package locksmyth

import CommonDef._
import just.semver.{ParseError, SemVer}
import locksmyth.dependency._
import sbt.Keys._
import sbt._

import java.time.Clock
import scala.annotation.tailrec

/** @author Kevin Lee
  */
object SbtLocksMyth extends AutoPlugin {

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    val SbtLocksMythPath: String = "SBT_LOCKSMYTH_PATH"

    val projectDependencyList: TaskKey[ProjectDependencies] =
      taskKey[ProjectDependencies]("Get dependencies per config per project")

    val listDependencies: TaskKey[Unit] =
      taskKey[Unit]("Print out the dependency list")

    val listNonEvictedDependencies: TaskKey[Unit] =
      taskKey[Unit]("Print out all non-evicted dependencies")

    val dependencyCrossProjectId: SettingKey[ModuleID] = SettingKey[ModuleID]("dependency-cross-project-id")

    val filterOutScalaLibrary: SettingKey[Boolean] = SettingKey[Boolean](
      "filter-scala-library",
      "Specifies if scala dependency should be filtered in dependency-* output"
    )

    val configsToModuleGraphsSbt: TaskKey[Seq[(Configuration, ModuleGraph)]] =
      TaskKey[Seq[(Configuration, ModuleGraph)]](
        "configs-module-graphs-sbt",
        "The dependency graph for a project as generated from SBT data structures."
      )

    val configsToModuleGraphs: TaskKey[Seq[(Configuration, ModuleGraph)]] =
      TaskKey[Seq[(Configuration, ModuleGraph)]]("configs-module-graphs", "The dependency graph for a project")

    val sbtLocksMythPath: SettingKey[String] =
      settingKey(
        s"The sbt dependency lock file path. It should be relative path (e.g. sbt-locksmyth.txt, sub-project/sbt-locksmyth.txt) " +
          s"It can be overridden by the env var, $SbtLocksMythPath [Default: sbt-locksmyth.txt]"
      )

    val isLockBaseProject: SettingKey[Boolean] =
      settingKey[Boolean](
        "The setting to indicate that the sbt lock file should be created from this project." +
          "There must be only one project having true for this setting as lock file may not work " +
          "if there are more than one lock base project. " +
          "Each base project can overwrite the existing one and the final lock file from one of the base project " +
          "might not be suitable for the other projects. [default: false]"
      )

    val writeLocksMyth: TaskKey[Unit] = taskKey[Unit]("Write lock's myth file.")
    val writeNonEvictedLocksMyth: TaskKey[Unit] = taskKey[Unit]("Write lock's myth file with no evicted dependencies.")
    val removeLocksMyth: TaskKey[Unit] = taskKey[Unit]("Remove lock's myth file.")

    def loadLockFile(file: File): Seq[ModuleID] = {
      @tailrec
      def collect(
        ls: List[String],
        errors: Vector[ModuleId.ParseError],
        modules: Vector[ModuleID]
      ): Either[Vector[ModuleId.ParseError], Vector[ModuleID]] = ls match {
        case Nil          =>
          if (errors.isEmpty) Right(modules) else Left(errors)
        case line :: rest =>
          if (line.trim.startsWith("#"))
            collect(rest, errors, modules)
          else {
            ModuleId.parse(line) match {
              case Left(error)           =>
                collect(rest, errors :+ error, modules)
              case Right(configModuleId) =>
                collect(rest, errors, modules :+ ModuleId.toModuleID(configModuleId))
            }
          }
      }

      val lines = IO.readLines(file, IO.utf8)
      collect(lines, Vector.empty, Vector.empty) match {
        case Left(errors)   =>
          throwMessageOnlyException(
            s"""Conflict found in the sbt lock file at $file
               |For more details, please check out the comments in the lock file.
               |---
               |${errors.map(ModuleId.renderError).mkString("\n")}
               |""".stripMargin
          )
        case Right(modules) =>
          modules
      }

    }

    def getSbtLockPath(p: String, log: Logger): File = {
      val path = sys
        .props
        .get(SbtLocksMythPath)
        .getOrElse(sys.env.getOrElse(SbtLocksMythPath, p))
      val lockFile = file(path).getCanonicalFile
      if (!lockFile.exists) {
        log.debug(s">>> The sbt lock file does not found at $path / ${lockFile.getPath}.")
      } else if (!lockFile.isFile) {
        throwMessageOnlyException(s"The sbt lock file path at $path / ${lockFile.getPath} exists but it is not a file.")
      } else {
        log.debug(s">>> sbt lock path: $path / ${lockFile.getPath}")
      }
      setSbtLockPath(lockFile.getPath)
      lockFile
    }
  }

  import autoImport._

  val configsForDependencyList: List[Configuration] = List(Compile, Test, IntegrationTest, Runtime, Provided, Optional)

  def handleProjectDependencies[A](
    projectName: ProjectName,
    projectDependencies: ProjectDependencies,
    scalaVersion: String
  )(handler: (ProjectName, UniqueDependencies) => Unit): Unit =
    if (projectName === projectDependencies.projectName) {
      handleDependencies(projectDependencies, scalaVersion)(handler)
    } else {
      ()
    }

  def handleDependencies[A](
    projectDependencies: ProjectDependencies,
    scalaVersion: String
  )(handler: (ProjectName, UniqueDependencies) => A): A =
    SemVer.parse(scalaVersion) match {
      case Left(error) =>
        throwMessageOnlyException(ParseError.render(error))

      case Right(semVer) =>
        val ProjectDependencies(projectName, configDependencies) = projectDependencies
        val projectNameWithScalaVersion = UniqueDependencies.projectNameWithScalaVersion(projectName, semVer)
        val uniqueDependencies =
          UniqueDependencies.toUniqueDependencies(projectNameWithScalaVersion, configDependencies)
        handler(projectName, uniqueDependencies)
    }

  /** Set the SBT_LOCKSMYTH_PATH system property so that every sub-project can have the same one sbt lock file.
    *
    * @param path Canonical path for sbt lock file.
    */
  def setSbtLockPath(path: String): Unit = {
    System.setProperty(SbtLocksMythPath, path)
    ()
  }

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      dependencyCrossProjectId := sbt.CrossVersion(scalaVersion.value, scalaBinaryVersion.value)(projectID.value),
      configsToModuleGraphsSbt / updateOptions := updateOptions.value.withCachedResolution(false),
      configsToModuleGraphsSbt :=
        configsForDependencyList
          .map(config =>
            Exposure
              ._dependencyTreeIgnoreMissingUpdate
              .value
              .configuration(config)
              .map { report =>
                (config, SbtUpdateReport.fromConfigurationReport(report, dependencyCrossProjectId.value))
              }
              .getOrElse((config, ModuleGraph.empty))
          ),
      configsToModuleGraphs / updateOptions := updateOptions.value.withCachedResolution(false),
      configsToModuleGraphs := {
        val moduleGraph = configsToModuleGraphsSbt.value

        if (filterOutScalaLibrary.value)
          ignoreScalaLibrary(moduleGraph)
        else
          moduleGraph
      },
      Global / filterOutScalaLibrary := true,
      projectDependencyList / updateOptions := updateOptions.value.withCachedResolution(false),
      projectDependencyList := ProjectDependencies(
        ProjectName(name.value),
        for {
          (config, graph) <- configsToModuleGraphs.value.toVector
          graphs = graph.modules
        } yield ConfigDependencies(config, graphs, graph.edges.toVector)
      ),
      listDependencies := {
        val log: Logger = sLog.value
        val thisProjectName = ProjectName(name.value)
        writeLocksMythWith(thisProjectName, projectDependencyList.value, scalaVersion.value)(
          uniqueDependencies =>
            renderDependencyList(thisProjectName, uniqueDependencies)(UniqueDependencies.renderElems),
          { content =>
            log.debug(content)
            println(content)

          }
        )
      },
      listNonEvictedDependencies := {
        val log = sLog.value
        val thisProjectName = ProjectName(name.value)
        writeLocksMythWith(thisProjectName, projectDependencyList.value, scalaVersion.value)(
          uniqueDependencies =>
            renderDependencyList(thisProjectName, uniqueDependencies)(UniqueDependencies.renderNonEvictedElems),
          { content =>
            log.debug(content)
            println(content)

          }
        )
      },
      sbtLocksMythPath := "sbt-locksmyth.txt",
      isLockBaseProject := false,
      writeLocksMyth := {
        val log: Logger = sLog.value
        val isItLockBaseProject = isLockBaseProject.value
        val projectDeps = projectDependencyList.value

        if (isItLockBaseProject) {
          val thisProjectName = ProjectName(name.value)
          val locksMythPath = sbtLocksMythPath.value
          val scalaV = scalaVersion.value
          val lockFile = getSbtLockPath(locksMythPath, log)
          writeLocksMythWith(thisProjectName, projectDeps, scalaV)(
            renderUniqueDependenciesForFile(_, UniqueDependencies.renderElems)(Clock.systemUTC()),
            { lockContent =>
              log.debug(lockContent)
              IO.write(lockFile, lockContent)
              log.info(s"Lock written: $lockFile")
            }
          )
        } else {
          ()
        }
      },
      writeNonEvictedLocksMyth := {
        val log: Logger = sLog.value
        val isItLockBaseProject = isLockBaseProject.value
        val projectDeps = projectDependencyList.value

        if (isItLockBaseProject) {
          val thisProjectName = ProjectName(name.value)
          val locksMythPath = sbtLocksMythPath.value
          val scalaV = scalaVersion.value
          val lockFile = getSbtLockPath(locksMythPath, log)
          writeLocksMythWith(thisProjectName, projectDeps, scalaV)(
            renderUniqueDependenciesForFile(_, UniqueDependencies.renderNonEvictedElems)(Clock.systemUTC()),
            { lockContent =>
              log.debug(lockContent)
              IO.write(lockFile, lockContent)
              log.info(s"Lock written: $lockFile")
            }
          )
        } else {
          ()
        }
      },
      removeLocksMyth := {
        if (isLockBaseProject.value) {
          val lockFile = getSbtLockPath(sbtLocksMythPath.value, sLog.value)
          if (lockFile.exists) {
            IO.delete(lockFile)
            sLog.value.info(s"Lock removed: $lockFile")
          } else {
            sLog.value.info(s"Lock Not Found: $lockFile")
          }
        }
        ()
      },
      dependencyOverrides := {
        val log = sLog.value
        val lockFile = getSbtLockPath(sbtLocksMythPath.value, sLog.value)
        val projectName = name.value

        if (lockFile.exists && lockFile.isFile) {
          log.info(s"[$projectName] loading lockFile: $lockFile")
          loadLockFile(lockFile)
        } else {
          log.info(s"[$projectName] no lock file found at $lockFile")
          dependencyOverrides.value
        }
      }
    )

  def renderUniqueDependenciesForFile(
    uniqueDependencies: UniqueDependencies,
    renderer: UniqueDependencies => Vector[String]
  )(clock: Clock): String = {
    val now = clock.instant()
    val epochMillis = now.toEpochMilli
    val deps = renderer(uniqueDependencies)
    s"""# This is an auto-generated file generated at $now ($epochMillis).
       |# Please do not modify it unless you need to solve version conflicts.
       |${deps.mkString("\n")}""".stripMargin
  }

  def renderDependencyList(projectName: ProjectName, uniqueDependencies: UniqueDependencies)(
    renderer: UniqueDependencies => Vector[String]
  ): String = {
    val deps = renderer(uniqueDependencies)
    s"""# Dependencies
       |===
       |## Project: ${projectName.projectName}
       |---
       |${deps.mkString("\n")}
       |===
       |""".stripMargin
  }

  def writeLocksMythWith(
    projectName: ProjectName,
    projectDeps: ProjectDependencies,
    scalaVersion: String
  )(renderer: UniqueDependencies => String, writer: String => Unit): Unit =
    handleProjectDependencies(projectName, projectDeps, scalaVersion) { (_, uniqueDependencies) =>
      val content = renderer(uniqueDependencies)
      writer(content)
    }

}
