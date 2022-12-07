// Modified version of https://github.com/guardian/guardian-management/blob/c7d99120927958757b35bea68931c4bd8690d64a/management/src/main/scala/com/gu/management/switchables.scala
package magenta

import java.util.concurrent.atomic.AtomicBoolean

/** This trait should be used by anything that wants to read the state of a
  * switch
  */
trait Switch {
  def isSwitchedOn: Boolean
  def isSwitchedOff: Boolean = !isSwitchedOn

  def opt[T](block: => T): Option[T] = if (isSwitchedOn) Some(block) else None
}

object Switch {
  object On {
    def unapply(switch: Switch): Boolean = switch.isSwitchedOn
  }

  object Off {
    def unapply(switch: Switch): Boolean = switch.isSwitchedOff
  }
}

/** This trait should be used by anything that wants to mutate the state of a
  * switch
  */
trait Switchable extends Switch {
  def switchOn(): Unit
  def switchOff(): Unit

  /** @return
    *   a single url-safe word that can be used to construct urls for this
    *   switch.
    */
  def name: String

  /** @return
    *   a sentence that describes, in websys understandable terms, the effect of
    *   switching this switch
    */
  def description: String
}

/** A simple implementation of Switchable that does the right thing in most
  * cases
  */
case class DefaultSwitch(
    name: String,
    description: String,
    initiallyOn: Boolean = true
) extends Switchable
    with Loggable {
  private val isOn = new AtomicBoolean(initiallyOn)

  def isSwitchedOn = isOn.get

  def switchOn(): Unit = {
    logger.info("Switching on " + name)
    isOn set true
  }

  def switchOff(): Unit = {
    logger.info("Switching off " + name)
    isOn set false
  }
}
