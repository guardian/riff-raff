package com.gu.deploy

import util.DynamicVariable


trait Output {
  def verbose(s: => String)
  def info(s: => String)
  def warn(s: => String)
  def error(s: => String)
  def context[T](s: => String)(block: => T): T
}

trait IndentingContext { this: Output =>

  val currentIndent = new DynamicVariable[String]("")

  def context[T](s: => String)(block: => T) = {
    info(s)
    currentIndent.withValue(currentIndent.value + "  ") {
      block
    }
  }

  def indent(s: String) = currentIndent.value + s

}

object BasicConsoleOutput extends Output with IndentingContext {
  def verbose(s: => String) { }
  def info(s: => String) { Console.out.println(indent(s)) }
  def warn(s: => String) { Console.out.println(indent(s)) }
  def error(s: => String) { Console.err.println(indent(s)) }

}

object Log extends Output {
  val current = new DynamicVariable[Output](BasicConsoleOutput)

  def verbose(s: => String) { current.value.verbose(s) }
  def info(s: => String) { current.value.info(s) }
  def warn(s: => String) { current.value.warn(s) }
  def error(s: => String) { current.value.error(s) }

  def context[T](s: => String)(block: => T) = { current.value.context(s)(block) }
}
