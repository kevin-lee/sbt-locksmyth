import ProjectInfo._
import just.semver.SemVer
import kevinlee.sbt.SbtCommon._
import sbt.ScmInfo

Global / sbtVersion := props.GlobalSbtVersion

ThisBuild / organization := "io.kevinlee"
ThisBuild / scalaVersion := props.ProjectScalaVersion
ThisBuild / description  := "Lock's Myth - sbt plugin to manage dependency lock"
ThisBuild / developers   := List(
  Developer(
    props.GitHubUsername,
    "Kevin Lee",
    "kevin.code@kevinlee.io",
    url(s"https://github.com/${props.GitHubUsername}")
  )
)
ThisBuild / homepage     := Some(url(s"https://github.com/${props.GitHubUsername}/${props.RepoName}"))
ThisBuild / scmInfo      :=
  Some(
    ScmInfo(
      url(s"https://github.com/${props.GitHubUsername}/${props.RepoName}"),
      s"git@github.com:${props.GitHubUsername}/${props.RepoName}.git"
    )
  )
ThisBuild / startYear    := Some(2020)

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name                              := props.ProjectName,
    scalacOptions ++= crossVersionProps(commonScalacOptions, SemVer.parseUnsafe(scalaVersion.value)) {
      case (SemVer.Major(2), SemVer.Minor(12), _) =>
        Seq("-Ywarn-unused-import", "-Ywarn-numeric-widen")
      case (SemVer.Major(2), SemVer.Minor(11), _) =>
        Seq("-Ywarn-numeric-widen")
      case _                                      =>
        Nil
    },
    Compile / console / scalacOptions := scalacOptions.value diff List("-Ywarn-unused-import", "-Xfatal-warnings"),
    Compile / compile / wartremoverErrors ++= commonWarts,
    Test / compile / wartremoverErrors ++= commonWarts,
    addCompilerPlugin("org.typelevel" % "kind-projector"     % "0.13.2" cross CrossVersion.full),
    addCompilerPlugin("com.olegpy"   %% "better-monadic-for" % "0.3.1"),
    libraryDependencies ++= Seq(libs.semVer) ++ libs.hedgehogLibs,
    testFrameworks ++= Seq(TestFramework("hedgehog.sbt.Framework")),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    publishMavenStyle                 := true,
  )

lazy val props = new {

  final val GitHubUsername = "Kevin-Lee"
  final val RepoName       = "sbt-locksmyth"
  final val ProjectName    = RepoName

  final val ProjectScalaVersion = "2.12.12"
  final val GlobalSbtVersion    = "1.4.0"
  final val hedgehogVersion     = "0.7.0"

}

lazy val libs = new {

  lazy val hedgehogLibs: Seq[ModuleID] = Seq(
    "qa.hedgehog" %% "hedgehog-core"   % props.hedgehogVersion % Test,
    "qa.hedgehog" %% "hedgehog-runner" % props.hedgehogVersion % Test,
    "qa.hedgehog" %% "hedgehog-sbt"    % props.hedgehogVersion % Test
  )

  lazy val semVer: ModuleID = "io.kevinlee" %% "just-semver" % "0.3.0"

}
