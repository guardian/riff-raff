package com.gu.deploy2

import util.DynamicVariable


trait Output {
  def verbose(s: => String)
  def info(s: => String)
  def warn(s: => String)
  def error(s: => String)
}

object BasicConsoleOutput extends Output {
  def verbose(s: => String) { }
  def info(s: => String) { Console.out.println(s) }
  def warn(s: => String) { Console.out.println(s) }
  def error(s: => String) { Console.err.println(s) }
}

object Log extends Output {
  val current = new DynamicVariable[Output](BasicConsoleOutput)

  def verbose(s: => String) { current.value.verbose(s) }
  def info(s: => String) { current.value.info(s) }
  def warn(s: => String) { current.value.warn(s) }
  def error(s: => String) { current.value.error(s) }
}
