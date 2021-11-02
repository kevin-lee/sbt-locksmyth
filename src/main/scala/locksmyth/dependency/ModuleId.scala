package locksmyth.dependency

import locksmyth.CommonDef._
import sbt.{Compile, Configuration, ModuleID, Provided, Test}

/** @author Kevin Lee
  */
final case class ModuleId(
  organisation: String,
  name: String,
  version: String
)

object ModuleId {

  implicit final class ModuleIdOps(private val moduleId: ModuleId) extends AnyVal {

    def keyString: String = s"${moduleId.organisation}:${moduleId.name}"
    def idString: String  = s"${moduleId.organisation}:${moduleId.name}:${moduleId.version}"

  }

  sealed trait ParseError
  object ParseError {
    final case class ConflictError(moduleId: ModuleId)                   extends ParseError
    final case class InvalidModuleId(moduleId: String)                   extends ParseError
    final case class UnsupportedConfig(config: String, moduleId: String) extends ParseError

    def conflictError(moduleId: ModuleId): ParseError                   = ConflictError(moduleId)
    def invalidModuleId(moduleId: String): ParseError                   = InvalidModuleId(moduleId)
    def unsupportedConfig(config: String, moduleId: String): ParseError = UnsupportedConfig(config, moduleId)
  }

  def parse(value: String): Either[ParseError, (Configuration, ModuleId)] =
    value.trim.split(":").toList match {
      case org :: name :: version :: config :: Nil =>
        val configuration = config match {
          case "compile" =>
            Right(Compile)

          case "test" =>
            Right(Test)

          case "provided" =>
            Right(Provided)

          case _ =>
            Left(ParseError.unsupportedConfig(config, value))
        }
        configuration.flatMap { configuration =>
          if (org.startsWith("*")) {
            Left(ParseError.conflictError(ModuleId(org.dropWhile(_ === '*').trim, name, version)))
          } else {
            Right((configuration, ModuleId(org, name, version)))
          }
        }

      case _ =>
        Left(ParseError.invalidModuleId(value))
    }

  def renderError(parseError: ParseError): String = parseError match {
    case ParseError.ConflictError(moduleId) =>
      s"Conflict found: ${moduleId.idString}"

    case ParseError.InvalidModuleId(moduleId) =>
      s"Invalid moduleId: $moduleId"

    case ParseError.UnsupportedConfig(config, moduleId) =>
      s"Unsupported configuration when parsing ModuleId. The config must be one of compile, test and provided. [config: $config, value $moduleId]"
  }

  def toModuleID(configModuleId: (Configuration, ModuleId)): ModuleID = {
    import sbt._
    val (config, moduleId) = configModuleId
    moduleId.organisation % moduleId.name % moduleId.version % config.name
  }

  def lessThan(a: ModuleId, b: ModuleId): Boolean = {
    val org = a.organisation.compareTo(b.organisation)
    if (org === 0) {
      val name = a.name.compareTo(b.name)
      if (name === 0) {
        /* roughly higher version first */
        b.version.compareTo(a.version) < 0
      } else {
        name < 0
      }
    } else {
      org < 0
    }
  }

  def sortModuleIds(modules: Vector[ModuleId]): Vector[ModuleId] =
    modules.sortWith(lessThan)

  def renderWithConfig(moduleId: ModuleId, config: Configuration): String =
    s"${moduleId.idString}:${config.name}"

}
