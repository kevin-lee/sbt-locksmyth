package locksmyth

import sbt.MessageOnlyException

/** @author Kevin Lee
  */
object CommonDef {

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyEquals[A](val self: A) extends AnyVal {
    def ===(other: A): Boolean = self == other
    def !==(other: A): Boolean = self != other
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def throwMessageOnlyException(message: String): Nothing =
    throw new MessageOnlyException(message)
}
