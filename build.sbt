import ProjectInfo._
import kevinlee.sbt.SbtCommon._
import just.semver.SemVer
import sbt.ScmInfo

val ProjectScalaVersion: String = "2.12.10"
val CrossScalaVersions: Seq[String] = Seq("2.10.7", ProjectScalaVersion)

val GlobalSbtVersion: String = "1.3.7"

val CrossSbtVersions: Seq[String] = Seq("0.13.17", GlobalSbtVersion)

val hedgehogVersion: String = "bd4e0cc785915e0af20d2a7ead5267d49b1de7b1"

val hedgehogRepo: Resolver =
  "bintray-scala-hedgehog" at "https://dl.bintray.com/hedgehogqa/scala-hedgehog"

val hedgehogLibs: Seq[ModuleID] = Seq(
    "qa.hedgehog" %% "hedgehog-core" % hedgehogVersion % Test
  , "qa.hedgehog" %% "hedgehog-runner" % hedgehogVersion % Test
  , "qa.hedgehog" %% "hedgehog-sbt" % hedgehogVersion % Test
  )

val justFp: ModuleID = "io.kevinlee" %% "just-fp" % "1.3.5"

val semVer: ModuleID = "io.kevinlee" %% "just-semver" % "0.1.0"


lazy val root = (project in file("."))
  .settings(
    organization := "io.kevinlee"
  , name         := "sbt-locksmyth"
  , scalaVersion := ProjectScalaVersion
  , version      := ProjectVersion
  , description  := "Lock's Myth - sbt plugin to manage dependency lock"
  , developers   := List(
      Developer("Kevin-Lee", "Kevin Lee", "kevin.code@kevinlee.io", url("https://github.com/Kevin-Lee"))
    )
  , homepage := Some(url("https://github.com/Kevin-Lee/sbt-locksmyth"))
  , scmInfo :=
      Some(ScmInfo(
        url("https://github.com/Kevin-Lee/sbt-locksmyth")
      , "git@github.com:Kevin-Lee/sbt-locksmyth.git"
    ))

  , startYear := Some(2020)
  , sbtPlugin := true
  , sbtVersion in Global := GlobalSbtVersion
  , crossSbtVersions := CrossSbtVersions
  , scalacOptions ++= crossVersionProps(commonScalacOptions, SemVer.parseUnsafe(scalaVersion.value)) {
        case (SemVer.Major(2), SemVer.Minor(12)) =>
          Seq("-Ywarn-unused-import", "-Ywarn-numeric-widen")
        case (SemVer.Major(2), SemVer.Minor(11)) =>
          Seq("-Ywarn-numeric-widen")
        case _ =>
          Nil
      }
  , scalacOptions in (Compile, console) := scalacOptions.value diff List("-Ywarn-unused-import", "-Xfatal-warnings")
  , wartremoverErrors in (Compile, compile) ++= commonWarts
  , wartremoverErrors in (Test, compile) ++= commonWarts
  , resolvers += hedgehogRepo
  , addCompilerPlugin("org.typelevel" % "kind-projector" % "0.10.3" cross CrossVersion.binary)
  , libraryDependencies ++= Seq(justFp, semVer) ++ hedgehogLibs
  , testFrameworks ++= Seq(TestFramework("hedgehog.sbt.Framework"))

  , licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
  , publishMavenStyle := false

  , bintrayPackageLabels := Seq("sbt", "plugin")
  , bintrayVcsUrl := Some("""git@github.com:Kevin-Lee/sbt-locksmyth.git""")
  , bintrayRepository := "sbt-plugins"

  , coverageHighlighting := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 10)) =>
        false
      case _ =>
        true
    })

)
