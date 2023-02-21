package sbt

import sbt.plugins.MiniDependencyTreeKeys.dependencyTreeIgnoreMissingUpdate

/** @author Kevin Lee
  * @since 2021-11-04
  */
object Exposure {

  def _dependencyTreeIgnoreMissingUpdate: TaskKey[UpdateReport] = dependencyTreeIgnoreMissingUpdate

}
