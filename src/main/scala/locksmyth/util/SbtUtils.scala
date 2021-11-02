package locksmyth.util

import just.semver.SemVer

/** @author Kevin Lee
  */
object SbtUtils {
  def getScalaBinaryVersion(scalaVersion: SemVer): String = scalaVersion match {
    case SemVer(SemVer.Major(major), SemVer.Minor(minor), _, _, _) =>
      if (major >= 3)
        major.toString
      else
        s"$major.$minor"
  }
}
