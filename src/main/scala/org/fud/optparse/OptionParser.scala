/* 
Copyright (c) 2011 Curt Sellmer

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.fud.optparse

import java.io.File
import collection.mutable.ListBuffer

/**
 * == Overview ==
 * OptionParser is a class that handles the parsing of switches and arguments on the command line.
 * It is based on the Ruby OptionParser class that is part of the standard Ruby library. It supports
 * POSIX style short option switches as well as GNU style long option switches.
 * By using closures when defining command line switches your code becomes much easier to write and maintain.  
 *
 * == Features ==
 * <ul>
 * <li>The argument specification and the code to handle it are written in the same place.</li>
 * <li>Automatically formats a help summary</li>
 * <li>Supports both short (-q) and long (--quiet) switches.</li>
 * <li>Long switch names can be abbreviated on the command line.</li>
 * <li>Switch arguments are fully typed so your code does not have to parse and covert them.</li>
 * <li>Switch arguments can be restricted to a certain set of values.</li>
 * <li>You can easily define your own argument parsers and/or replace the default ones.</li>
 * </ul>
 *
 * == Dependencies ==
 * This code requires Scala 2.8 as it relies on `ClassManifest`.
 *
 * == Defining Switches ==
 * You define a switch by supplying its name(s), description, and a function that will be 
 * called each time the switch is detected in the list of command line arguments.
 * Your function has the type `{ value: T => Unit }`.  You supply the type for `T` and the framework
 * will select the appropriate parser and call your function with the value converted to your
 * expected type. {{{
 * var revision = 0
 * val cli = new OptionParser
 * cli.reqd("-r", "--revision NUM", "Choose revision") { v: Int => revision = v }
 * val args = cli.parse(List("-r", "9"))
 * }}}
 * The `reqd()` function defines a switch that takes a required argument.  In this case we have
 * specified that we expect the argument value to be an `Int`.  If the user enters a value that
 * is not a valid integer then an [[org.fud.optparse.OptionParserException]] is thrown with an appropriate error 
 * message.  If the value is valid then our supplied function is called with the integer value.
 * Here we simply save the value in a variable.  Anything encountered on the command line that
 * is not a switch or an argument to a switch is returned in a list by the parse function.
 *
 * == Switch Names ==
 * A switch may have a short name, a long name, or both.
 *
 * Short names may be specified as a single character preceded by a single dash.  You may optionally
 * append an argument name separated by a space.  The argument name is only for documentation
 * purposes and is displayed with the help text. {{{
 *   -t           <== no argument
 *   -t ARG       <== required argument
 *   -t [VAL]     <== optional argument
 * }}}
 * Long names may be any number of characters and are preceded by two dashes. You may optionally
 * append an argument name.  The argument name may be separated by a space or by an equals sign.
 * This will affect how the name is displayed in the help text. {{{
 *   --quiet
 *   --revision REV
 *   --revision=REV
 *   --start-date [TODAY] 
 *   --start-date=[TODAY] 
 *   --start-date[=TODAY] 
 * }}}
 * Notice that in the case of an optional parameter you may put the equal sign inside or outside
 * the bracket.  Again this only affects how it is dispalyed in the help message.  If you specify
 * an argument name with both the short name and the long name, the one specified with the long
 * name is used.
 * 
 * There is a boolean switch that does not accept a command line argument but may be negated
 * when using the long name by preceding the name with `no-`.  For example: {{{
 *  cli.bool("-t", "--timestamp", "Generate a timestamp") { v => if (v) generateTimestamp() }
 *
 *   can be specified on the command line as:
 *     -t                <== function called with v == true
 *     --timestamp       <== function called with v == true
 *     --no-timestamp    <== function called with v == false
 *     --no-t            <== function called with v == false  (using partial name)
 * }}}
 * Notice that you only specify the positive form of the name when defining the switch. The help 
 * text for this switch looks like this: {{{
 *    -t, --[no-]timestamp            Generate a timestamp
 * }}}
 * == Special Tokens ==
 * <ul>
 * <li>`--` is iterpreted as the ''end of switches''.  When encountered no following arguments on the
 * command line will be treated as switches.</li>
 * <li>`-` is interpreted as a normal argument and not a switch.  It is commonly used to indicate `stdin`.</li>
 * </ul>
 * == Switch Types == 
 * You can define switches that take no arguments, an optional argument, or a required argument.
 * {{{
 * - Flag
 *       cli.flag("-x", "--expert", "Description") { () => ... }
 *
 * - Boolean
 *       cli.bool("-t", "--timestamp", "Description") { v: Boolean => ... }
 *
 * - Required Argument 
 *       cli.reqd("-n", "--name=NAME", "Description") { v: String => ... }
 *
 * - Optional Argument 
 *       cli.optl("-d", "--start-date[=TODAY]", "Description") { v: Option[String] => ... }
 *
 * - Comma Separated List 
 *       cli.list("-b", "--branches=B1,B2,B3", "Description") { v: List[String] => ... }
 * }}}
 *
 * == Limiting Values ==
 * For switches that take arguments, either required or optional, you can specify a list of
 * acceptable values. {{{
 * def setColor(c: String) = ...
 * cli.reqd("", "--color COLOR", List("red", "green", "blue")) { v => setColor(v) }
 * }}}
 * Here if the user enters `--color purple` on the command line an [[org.fud.optparse.OptionParserException]] is
 * thrown.  The exception message will display the accepable values.  Also the user can enter
 * partial values. {{{
 * coolapp --color r     // <==  Will be interpreted as red
 * }}}
 * If the value entered matches two or more of the acceptable values then an [[org.fud.optparse.OptionParserException]] is
 * thrown with a message indicating that the value was ambiguous and displays the acceptable values.
 * Also note that you can pass a `Map` instead of a `List` if you need to map string values to
 * some other type. {{{
 * class Color(rgb: String)
 * val red   = new Color("#FF0000")
 * val green = new Color("#00FF00")
 * val blue  = new Color("#0000FF")
 * def setColor(c: Color) = ...
 * cli.optl("", "--color [ARG]", Map("red" -> red, "green" -> green, "blue" -> blue)) { v => 
 *   setColor(v.getOrElse(red))
 * }
 * }}}
 * Notice here that we did not have to specify the type of the function parameter `v` because the
 * compiler can infer the type from the `Map[String, Color]` parameter. Since we are defining a
 * switch with an optional argument, the type of `v` is `Option[Color]`.
 *
 * == Banner, Separators and Help Text ==
 * You can specify a banner which will be the first line displayed in the help text. You can also
 * define separators that display information between the switches in the help text. {{{
 * val cli = new OptionParser
 * cli.banner = "coolapp [Options] file..."
 * cli separator ""
 * cli separator "Main Options:"
 * cli.flag("-f", "--force", "Force file creation") { () => ... }
 * cli.reqd("-n NAME", "", "Specify a name") { v: String => ... }
 * cli separator ""
 * cli separator "Other Options:"
 * cli.optl("", "--backup[=NAME]", "Make a backup", "--> NAME defaults to 'backup'") { v: Option[String] => ... }
 * cli.bool("-t", "--timestamp", "Create a timestamp") { () => ... }
 * println(cli)  // or println(cli.help)
 * 
 * Would print the following:
 * coolapp [Options] file...
 *
 * Main Options:
 *     -f, --force                  Force file creation
 *     -n NAME                      Specify a name
 *
 * Other Options:
 *         --backup[=FILE]          Make a backup
 *                                  --> FILE defaults to 'backup'
 *     -t, --[no-]timestamp         Create a timestamp
 *     -h, --help                   Show this message
 * }}}
 * Where did the `-h, --help` entry come from?  By default the `-h` switch is added automatically.
 * The function associated with it will print the help text to `stdout` and call `exit(0)`.  
 * You can define your own help switch by simply defining a switch with either or both of the
 * names `-h`, `--help`.  You can also turn off the auto help altogether.
 *
 * == How Short Switches Are Parsed == 
 * Short switches encountered on the command line are interpreted as follows:
 * {{{
 * Assume that the following switches have been defined:
 *    -t, --text   (Takes no argument) 
 *    -v           (Takes no argument) 
 *    -f FILE      (Requires an argument) 
 *    -b [OPT]     (Takes an optional argument) 
 *
 * Switches that do not accept arguments may be specified separately or may be concatenated together:
 *    -tv  ==  -t -v
 *
 * A switch that takes an argument may be concatenated to one or more switches that do not take 
 * arguments as long as it is the last switch in the group:
 *     -tvf foo.tar  ==  -t -v -f foo.tar
 *     -tfv foo.tar  ==  -t -f v foo.tar  (v is the argument value for the -f switch)
 *
 * The argument for a switch may be specified with or without intervening spaces:
 *     -ffoo.tar  == -f foo.tar
 *
 * For arguments separated by space, switches with required arguments are greedy while those that take
 * optional arguments are not. They will ignore anything that looks like a another switch.
 *    -v -f -t       <-- The -f option is assigned the value "-t"
 *    -v -f -text    <-- The -f option is assigned the value "-text"
 *    -v -f --text   <-- The -f option is assigned the value "--text"
 *
 *    -v -b t        <-- The -b option is assigned the value "t"
 *    -v -b -t       <-- The -b option is interpreted without an argument
 *    -v -b -text    <-- The -b option is interpreted without an argument
 *    -v -b --text   <-- The -b option is interpreted without an argument
 *    -v -b-text     <-- The -b option is assigned the value "-text" (no intervening space)
 * }}}
 * == How Long Switches Are Parsed == 
 * Long swithes encountered on the command line are interpreted as follows:
 * {{{
 * Assume that the following switches have been defined:
 *    --timestamp       (Boolean - takes no argument) 
 *    --file FILE       (Requires an argument) 
 *    --backup[=BACKUP] (Takes an optional argument) 
 *
 * The argument for a switch may be joined by and equals sign or may be separated by space:
 *     --file=foo.tar == --file foo.tar
 *     --backup=data.bak == --backup data.bak
 *
 * For arguments separated by space, switches with required arguments are greedy while those that take
 * optional arguments are not. They will ignore anything that looks like a another switch. See the
 * discussion of short switches above for an example.  The behavior for long switches is identical.
 *
 * Boolean switches may be negated.  
 *     --timestamp      <-- The option is assigned a true value
 *     --no-timestamp   <-- The option is assigned a false value
 * }}}
 * == Full Example ==
 *
 * {{{
 * import java.util.Date
 * import java.io.File
 * import java.text.{SimpleDateFormat, ParseException}
 * import org.fud.optparse._
 * 
 * object Sample {
 *   val dateFormat = new SimpleDateFormat("MM-dd-yyyy")
 * 
 *   def main(args: Array[String]) {
 *     var options = Map[Symbol, Any]('quiet -> false, 'expert -> false, 'base -> "HEAD")
 *     var libs = List[File]()
 *     val file_args = try {
 *       new OptionParser {
 *         // Add an argument parser to handle date values
 *         addArgumentParser[Date] { arg =>
 *           try   { dateFormat.parse(arg) }
 *           catch { case e: ParseException => throw new InvalidArgumentException("Expected date in mm-dd-yyyy format") }
 *         }
 *         banner = "coolapp [options] file..."
 *         separator("")
 *         separator("Options:")
 *         bool("-q", "--quiet",           "Do not write to stdout.") 
 *           { v => options += 'quiet -> v }
 *
 *         flag("-x", "",                  "Use expert mode")
 *           { () => options += 'expert -> true }
 *
 *         reqd[String]("-n <name>", "",           "Enter you name.")
 *           { v => options += 'name -> v }
 *
 *         reqd[File]("-l", "--lib=<lib>",       "Specify a library. Can be used mutiple times.")
 *           { v => libs = v :: libs }
 *
 *         reqd[Date]("-d", "--date <date>",     "Enter date in mm-dd-yyyy format.")
 *           { v => options += 'date -> v }
 *
 *         reqd[String]("-t", "--type=<type>", List("ascii", "binary"), "Set the data type. (ascii, binary)") 
 *           { v => options += 'type -> v }
 *
 *         optl[String]("-b", "--base[=<commit>]", "Set the base commit. Default is HEAD.")
 *           { v: Option[String] => options += 'base -> v.getOrElse("HEAD") }
 *       }.parse(args)
 *     }
 *     catch { case e: OptionParserException => println(e.getMessage); exit(1) }
 * 
 *     println("Options: " + options)
 *     println("Libraries: " + libs.reverse)
 *     println("File Args: " + file_args)
 *   }
 * }
 *
 * Command Line: -l /etc/foo --lib=/tmp/bar -x .profile -n Bob -d09-11-2001
 * -------------------------------------------------------------------------------
 * Options: Map('name -> Bob, 'quiet -> false, 'base -> HEAD, 
 *              'date -> Tue Sep 11 00:00:00 CDT 2001, 'expert -> true)
 * Libraries: List(/etc/foo, /tmp/bar)
 * File Args: List(.profile)
 *
 * Command Line: --date=04/01/2011
 * -------------------------------------------------------------------------------
 * invalid argument: --date=04/01/2011   (Expected date in mm-dd-yyyy format)
 *
 * Command Line: --ty=ebcdic
 * -------------------------------------------------------------------------------
 * invalid argument: --ty ebcdic    (ascii, binary)
 *
 * Command Line: --ty=a
 * -------------------------------------------------------------------------------
 * Options: Map('quiet -> false, 'expert -> false, 'base -> HEAD, 'type -> ascii)
 * Libraries: List()
 * File Args: List()
 
 * }}}
 *
 * @author Curt Sellmer
 */

class OptionParser {
  /** Set this to `false` to avoid the automatically added help switch.
   *
   * The action for the added help switch is to print the help text to `stdout` and then
   * call `exit(0)`.
   *
   * You can also override the default help switch by adding your own switch with a
   * short name of "-h" or a long name of "--help". */
  var auto_help = true
  
  protected val argv = new ListBuffer[String]
  protected var switches = new ListBuffer[Switch]
  
  protected var curr_arg_display = ""  // Used for error reporting
  
  /** Returns the formatted help text as a String */
  def help: String = {
    add_auto_help
    (if (banner.isEmpty) "" else banner + "\n") + switches.mkString("\n")
  }
  /** Same as calling help. */
  override def toString = help
  
  protected def add_auto_help: Unit = {
    if (auto_help && !switches.exists(s => s.names.short == "h" || s.names.long == "--help"))
      this.flag("-h", "--help", "Show this message") { () => println(this); sys.exit(0) }
  }
  
  /** Set the banner that is displayed as the first line of the help text. */
  var banner = ""

  /** Add a message to the help text.
   *
   *  It will be displayed at the left margin after any previously defined switches/separators. */
  def separator(text: String) = addSwitch(new Separator(text))
  
  /** Define a switch that takes no arguments. */
  def flag(short: String, long: String, info: String*)(func: () => Unit): Unit =
    addSwitch(new NoArgSwitch(getNames(short, long), info, func))

  /**
   * Define a boolean switch.  
   * This switch takes no arguments.  The long form of the switch may be prefixed with no- to negate the switch.
   * For example a switch with long name  --expert could be specified as --no-expert on the command line. */
  def bool(short: String, long: String, info: String*)(func: Boolean => Unit): Unit =
    addSwitch(new BoolSwitch(getNames(short, long, true), info, func))

  /** Define a switch that takes a required argument. */
  def reqd[T](short: String, long: String, info: String*)(func: T => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ArgSwitch(getNames(short, long), info, arg_parser(m), func))
  
  /**  Define a switch that takes a required argument where the valid values are given by a Seq[]. */
  def reqd[T](short: String, long: String, vals: Seq[T], info: String*)(func: T => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))

  /** Define a switch that takes a required argument where the valid values are given by a Map. */
  def reqd[T](short: String, long: String, vals: Map[String, T], info: String*)(func: T => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))

  /** Define a switch that takes an optional argument. */
  def optl[T](short: String, long: String, info: String*)(func: Option[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new OptArgSwitch(getNames(short, long), info, arg_parser(m), func))

  /** Define a switch that takes an optional argument where the valid values are given by a Seq[]. */
  def optl[T](short: String, long: String, vals: Seq[T], info: String*)(func: Option[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new OptArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))
  
  /** Define a switch that takes an optional argument where the valid values are given by a Map. */
  def optl[T](short: String, long: String, vals: Map[String, T], info: String*)(func: Option[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new OptArgSwitchWithVals(getNames(short, long), info, new ValueList(vals), func))
  
  /** Define a switch that takes a comma separated list of arguments. */
  def list[T](short: String, long: String, info: String*)(func: List[T] => Unit)(implicit m: ClassManifest[T]): Unit =
    addSwitch(new ListArgSwitch(getNames(short, long), info, arg_parser(m), func))

  
  /**
   * Parse the given command line. 
   * Each token from the command line should be in a separate entry in the given sequence such
   * as the array of strings passed to `def main(args: Array[String]) {}`.
   * The option switches are processed using the previously defined switches.  All non-switch
   * arguments are returned as a list of strings. 
   *
   * If any problems are encountered an [[org.fud.optparse.OptionParserException]] is thrown.
   */
  def parse(args: Seq[String]): List[String] = {
    // Pluck a switch argument from argv. If greedy we always take it.
    // If not we take it if it does not begin with a dash.
    // (Special case: A single '-' represents the stdin arg and is plucked)
    def pluckArg(greedy: Boolean): Option[String] = {
      if (greedy && argv.isEmpty) throw new ArgumentMissing
      
      if (argv.nonEmpty && (greedy || !(argv(0).startsWith("-") && argv(0).length > 1))) {
        val a = argv.remove(0)
        curr_arg_display += (" " + a)  // Update for error reporting
        Some(a)
      }
      else
        None
    }
    
    add_auto_help

    val non_switch_args = new ListBuffer[String]
    argv.clear // Clear any remnants
    argv ++= args
    
    var terminate = false
    while (!terminate) {
      nextToken match {
        case Terminate()    => non_switch_args ++= argv; terminate = true
        case NonSwitch(arg) => non_switch_args += arg
        case SwitchToken(switch, longForm, joinedArg, negated) =>
          var arg = (joinedArg, switch.takesArg, longForm) match {
            case (Some(a), true,  _)     => Some(a)
            case (Some(a), false, true)  => throw new NeedlessArgument
            case (Some(a), false, false) => ("-" + a) +=: argv; None  // short switches can be joined so put the arg back with a - prefix                
            case (None,    false, _)     => None
            case (None,    true,  _)     => pluckArg(switch.requiresArg)
          }
          switch.process(arg, negated)
      }
    }

    non_switch_args.toList
  }
  
  /**
   * Add an argument parser for a specific type.
   *
   * Parsers for the these types are provided by default:
   * <ul>
   * <li>`String`</li>
   * <li>`Int`</li>
   * <li>`Short`</li>
   * <li>`Long`</li>
   * <li>`Float`</li>
   * <li>`Double`</li>
   * <li>`Char`</li>
   * <li>`java.io.File`</li>
   * </ul>
   * A parser is simply a function that takes a single `String` argument and returns a value of
   * the desired type.  If your parser detects an invalid argument it should throw 
   * an [[org.fud.optparse.InvalidArgumentException]].  You can supply a message in the exception
   * that indicates why the argument was not valid.
   *
   * If you add a parser for a type that already has a parser, the existing parser will be replaced.
   */
  def addArgumentParser[T](f: String => T)(implicit m: ClassManifest[T]): Unit = {
    val wrapped = { s: String =>
      try { f(s) } 
      catch { 
        // Convert to internal exceptions so we can prefix the message with the erroneous input.
        case e: InvalidArgumentException   => throw new InvalidArgument(e)
        case e: AmbiguousArgumentException => throw new AmbiguousArgument(e)
      } 
    }
    arg_parsers = (m -> wrapped) :: arg_parsers
  }
    
  protected class ArgumentMissing extends OptionParserException("argument missing: " + curr_arg_display)
  protected class InvalidArgument(m: String) extends OptionParserException("invalid argument: " + curr_arg_display + m) {
    def this(e: InvalidArgumentException) = this(if (e.getMessage.isEmpty) "" else "   (%s)".format(e.getMessage))
  }
  protected class AmbiguousArgument(m: String) extends OptionParserException("ambiguous argument: " + curr_arg_display + m) {
    def this(e: AmbiguousArgumentException) = this(if (e.getMessage.isEmpty) "" else "   (%s)".format(e.getMessage))
  }
  protected class NeedlessArgument extends OptionParserException("needless argument: " + curr_arg_display)
  protected class InvalidOption extends OptionParserException("invalid option: " + curr_arg_display)
  protected class AmbiguousOption(m: String) extends OptionParserException("ambiguous option: " + curr_arg_display + m) {
    def this() = this("")    
  }
  
  protected abstract class Token
  
  protected case class Terminate() extends Token

  protected case class NonSwitch(arg: String) extends Token

  // Container for internal and display names of a switch.
  protected case class Names(short: String, long: String, display: String) {
    def longNegated = "no-" + long
    override val toString = display
  }
  
  // A switch was parsed on the command line.
  // We indicate where the long form (eg. --type) was used and
  // if there was a joined token:
  //   -tbinary  or  --type=binary 
  protected case class SwitchToken(switch: Switch, longForm: Boolean, joinedArg: Option[String], negated: Boolean) extends Token
  
  
  // The short and long names are stored without leading '-' or '--'
  protected abstract class Switch(val names: Names, val info: Seq[String] = List()) extends Token {
    val takesArg: Boolean = false
    val requiresArg: Boolean = false
    def exactMatch(lname: String) = lname == names.long
    def partialMatch(lname: String) = names.long.startsWith(lname)
    def negatedMatch(lname: String) = false
    
    // Called when this switch is detected on the command line.  Should handle the
    // invocation of the user's code to process this switch.
    //   negated param only used by BoolSwitch
    def process(arg: Option[String], negated: Boolean): Unit = {}
    
    override lazy val toString = {
      val sw   = "    " + names
      val sep  = "\n" + " " * 37
      val sep1 = if (sw.length < 37) " " * (37 - sw.length) else sep
      sw + (if (info.isEmpty) "" else info.mkString(sep1, sep, ""))
    }
  }
  
  protected class Separator(text: String) extends Switch(Names("", "", ""), Seq()) {
    override lazy val toString = text
  }
  
  protected class NoArgSwitch(n: Names, d: Seq[String], func: () => Unit) extends Switch(n, d) {
    override def process(arg: Option[String], negated: Boolean): Unit = func()
  }
  
  protected class BoolSwitch(n: Names, d: Seq[String], func: Boolean => Unit) extends Switch(n, d) {
    // override the match functions to handle the negated name
    override def exactMatch(lname: String) = lname == names.long || lname == names.longNegated
    override def partialMatch(lname: String) = names.long.startsWith(lname) || names.longNegated.startsWith(lname)
    // Return true if the given lname is a prefix match for our negated name.
    override def negatedMatch(lname: String) = names.longNegated.startsWith(lname)
    
    override def process(arg: Option[String], negated: Boolean): Unit = func(!negated)
  }
  
  protected class ArgSwitch[T](n: Names, d: Seq[String], parse_arg: String => T, func: T => Unit) extends Switch(n, d) {
    override val takesArg    = true
    override val requiresArg = true
    
    override def process(arg: Option[String], negated: Boolean): Unit = arg match {
      case None => throw new RuntimeException("Internal error - no arg for ArgSwitch")
      case Some(a) => func(parse_arg(a))
    }
  }

  protected class OptArgSwitch[T](n: Names, d: Seq[String], parse_arg: String => T, func: Option[T] => Unit) extends Switch(n, d) {
    override val takesArg = true
    override def process(arg: Option[String], negated: Boolean): Unit = func(arg.map(parse_arg))
  }
  
  protected class ListArgSwitch[T](n: Names, d: Seq[String], parse_arg: String => T, func: List[T] => Unit) extends Switch(n, d) {
    override val takesArg    = true
    override val requiresArg = true
    override def process(arg: Option[String], negated: Boolean): Unit = arg match {
      case None => throw new RuntimeException("Internal error - no arg for ListArgSwitch")
      case Some(argList) => func(argList.split(",").toList.map(parse_arg))
    }
  }
    
  // Clas to hold a list of valid values. Maps the string representation to it's actual value.
  // Support partial matching on the strings
  protected class ValueList[T](vals: List[(String, T)]) {
    def this(l: Seq[T]) = this(l.toList.map(v => (v.toString, v)))
    def this(m: Map[String, T]) = this(m.toList)
    
    def get(arg: String): T = {
      def display(l: List[(String, T)]): String = "    (%s)".format(l.map(_._1).mkString(", "))
      vals.filter(_._1.startsWith(arg)).sortWith(_._1.length < _._1.length) match {
        case x :: Nil => x._2
        case x :: xs  => if (x._1 == arg) x._2 else throw new AmbiguousArgument(display(x :: xs))
        case Nil => throw new InvalidArgument(display(vals))
      }
    }
  }
  
  protected class ArgSwitchWithVals[T](n: Names, d: Seq[String], vals: ValueList[T], func: T => Unit) extends Switch(n, d) {
    override val takesArg    = true
    override val requiresArg = true
    
    override def process(arg: Option[String], negated: Boolean): Unit = arg match {
      case None => throw new RuntimeException("Internal error - no arg for ArgSwitchWithVals")
      case Some(a) => func(vals.get(a))
    }
  }

  protected class OptArgSwitchWithVals[T](n: Names, d: Seq[String], vals: ValueList[T], func: Option[T] => Unit) extends Switch(n, d) {
    override val takesArg = true
    override def process(arg: Option[String], negated: Boolean): Unit = func(arg.map(vals.get))
  }

  // Add a new switch to the list.  If any existing switch has the same short or long name
  // as the new switch then it is first removed.  Thus a new switch can potentially replace
  // two existing switches.
  protected def addSwitch(switch: Switch): Unit = {
    def remove(p: Switch => Boolean): Unit = 
      switches.findIndexOf(p) match {
        case -1  =>
        case idx => switches.remove(idx)
      }
    
    if (switch.names.short != "") remove(_.names.short == switch.names.short)
    if (switch.names.long  != "") remove(_.names.long  == switch.names.long)
    switches += switch
  }
  
  private val ShortSpec   = """-(\S)(?:\s+(.+))?"""r
  private val LongSpec    = """--([^\s=]+)(?:(=|\[=|\s+)(\S.*))?"""r
  private val LongNegated = """--no-.*"""r
  // Parse the switch names and return the 'fixed' names.
  // Short name: 
  //   - must begin with a single '-'
  //   - may only be one character
  //   - may be followed by an argument name separated by whitespace.
  //        This is for documentation purposes when the help method is called.
  // Long name:
  //   - must begin with two '--'
  //   - cannot begin with '--no-'  This is reserved for negating boolean arguments.
  //   - may be followed by a argument. If present the argument must be separated from
  //     the long name in one of the following ways:
  //       1.  spaces  #=> --name NAME, --name [NAME]
  //       2.  =       #=> --name=NAME, --name=[NAME]
  //       3.  [=      #=> --name[=NAME]
  //    This is for documentation purposes when the help method is called.
  //
  // If an arg is specified for both the short and long, then the long one will take precedence.
  protected def getNames(shortSpec: String, longSpec: String, forBool: Boolean = false): Names = {
    var short = ""
    var long  = ""
    var arg   = ""
    var l_delim = " "
    shortSpec.trim match {
      case "" =>
      case ShortSpec(n, a) if a == null => short = n
      case ShortSpec(n, a) => short = n; arg = a
      case x => throw new OptionParserException("Invalid short name specification: " + x)
    }

    longSpec.trim match {
      case "" =>
      case LongNegated() => throw new OptionParserException("Invalid long name specification: " + longSpec.trim + "  (The prefix '--no-' is reserved for boolean options)")
      case LongSpec(n, _, a) if a == null => long = n
      case LongSpec(n, d, a) => long = n; l_delim = d.substring(0, 1); arg = a
      case x => throw new OptionParserException("Invalid long name specification: " + x)
    }
    
    val ldisp = if (forBool) "[no-]" + long else long
    val display = (short, long, arg) match {
      case ("", "",  _) => throw new OptionParserException("Both long and short name specifications cannot be blank")
      case (s,  "", "") => "-%s".format(s)
      case ("",  l, "") => "    --%s".format(ldisp)
      case (s,  "",  a) => "-%s %s".format(s, a)
      case (s,   l, "") => "-%s, --%s".format(s, ldisp)
      case ("",  l,  a) => "    --%s%s%s".format(ldisp, l_delim, a)
      case (s,   l,  a) => "-%s, --%s%s%s".format(s, ldisp, l_delim, a)
    }
    Names(short, long, display)
  }
    
  // A list of registered argument parsers
  protected var arg_parsers = List[(ClassManifest[_], String => _)]()
  
  // Look up an argument parser given a ClassManifest.
  // Throws an exception if not found.
  protected def arg_parser[T](m: ClassManifest[T]): String => T = {
    arg_parsers.find(m == _._1).map(_._2.asInstanceOf[String => T]).getOrElse {
      throw new OptionParserException("No argument parser found for " + m)
    }
  }
  
  // Look up a switch by the given long name.
  // Partial name lookup is performed. If more than one match is found then if one is an
  // exact match it wins, otherwise and AmbiguousOption exception is thrown
  // Throws InvalidOption if the switch cannot be found
  protected def longSwitch(name: String, arg: Option[String]): SwitchToken = {
    def display(l: List[Switch]): String =
      "    (%s)".format(l.map(s => (if (s.negatedMatch(name)) "--no-" else "--") + s.names.long).mkString(", "))
    
    val switch = switches.toList.filter(_.partialMatch(name)).sortWith(_.names.long.length < _.names.long.length) match {
      case x :: Nil => x
      case x :: xs  => if (x.exactMatch(name)) x else throw new AmbiguousOption(display(x :: xs))
      case Nil => throw new InvalidOption
    }
    SwitchToken(switch, true, arg, switch.negatedMatch(name))
  }
  
  // Look up a swith by the given short name.  Must be an exact match.
  protected def shortSwitch(name: String, arg: Option[String]): SwitchToken = {
    val switch = switches.find(_.names.short == name).getOrElse { throw new InvalidOption }
    SwitchToken(switch, false, arg, false)
  }
  
  private val TerminationToken   = "--"r
  private val StdinToken         = "(-)"r
  private val LongSwitchWithArg  = "--([^=]+)=(.*)"r
  private val LongSwitch         = "--(.*)"r
  private val ShortSwitch        = "-(.)(.+)?"r
  
  // Get the next token from the argv buffer.
  protected def nextToken: Token = {
    if (argv.isEmpty)
      Terminate()
    else {
      curr_arg_display = argv(0)
      argv.remove(0) match {
        // The order of the cases here is important!
        case TerminationToken()           => Terminate()
        case StdinToken(arg)              => NonSwitch(arg)
        case LongSwitchWithArg(name, arg) => longSwitch(name, Some(arg))
        case LongSwitch(name)             => longSwitch(name, None)
        case ShortSwitch(name, null)      => shortSwitch(name, None)
        case ShortSwitch(name, arg)       => shortSwitch(name, Some(arg)) 
        case arg                          => NonSwitch(arg)
      }
    }
  }
  
  private val HexNum = """(-)?(?i:0x([0-9a-f]+))""".r
  private val OctNum = """(-)?0([0-7]+)""".r
  private def numWithRadix(num: String): (String, Int) = {
    num match {
      case HexNum(sign, n) => ((if (sign == null) "" else sign) + n, 16)
      case OctNum(sign, n) => ((if (sign == null) "" else sign) + n, 8)
      case _ => (num, 10)
    }
  }
  
  // ==========================================================================================
  // Define default argument parsers

  addArgumentParser[String] {arg => arg}
  
  addArgumentParser[Int] { arg => 
    try { 
      val n = numWithRadix(arg)
      java.lang.Integer.parseInt(n._1, n._2)
    }  
    catch { case _: NumberFormatException => throw new InvalidArgumentException("Integer expected") }
  }
  
  addArgumentParser[Short] { arg => 
    try {
      val n = numWithRadix(arg)
      java.lang.Short.parseShort(n._1, n._2)
    } 
    catch { case _: NumberFormatException => throw new InvalidArgumentException("Short expected") }
  }
  
  addArgumentParser[Long] { arg => 
    try {
      val n = numWithRadix(arg)
      java.lang.Long.parseLong(n._1, n._2)
    }
    catch { case _: NumberFormatException => throw new InvalidArgumentException("Long expected") }
  }
  
  addArgumentParser[Float] { arg => 
    try { 
      val f = arg.toFloat
      if (f == Float.PositiveInfinity || f == Float.NegativeInfinity) throw new NumberFormatException
      f
    } 
    catch { case _: NumberFormatException => throw new InvalidArgumentException("Float expected") }
  }
  
  addArgumentParser[Double] { arg => 
    try { 
      val d = arg.toDouble
      if (d == Double.PositiveInfinity || d == Double.NegativeInfinity) throw new NumberFormatException
      d
    } 
    catch { case _: NumberFormatException => throw new InvalidArgumentException("Double expected") }
  }
  
  // Char parser
  // Can be a single character
  // \b	: backspace BS
  // \t	: horizontal tab HT
  // \n	: linefeed LF
  // \f	: form feed FF
  // \r	: carriage return CR
  //
  // \ N N N  where NNN where NNN are there octal digits defining an ASCII character code (000 - 377).
  // \ x NN  where NN are hex digits defining an ASCII character code (00 - FF).
  // \ u N N N N  where NNNN are four hex digits defining a UNICODE character code.
  //
  // Note that when passing these from most shells you will have to escape the backslash!
  private val SINGLE  = "(.)".r
  private val OCTAL   = """\\([0-3][0-7]{2}|[0-7]{1,2})""".r
  private val HEX     = """\\[xX]([0-9a-fA-F]{1,2})""".r
  private val UNICODE = """\\u([0-9a-fA-F]{4})""".r
  addArgumentParser[Char] { arg => 
    import java.lang.Integer
    arg match {
      case "\\b"			=> '\u0008' // BS - backspace
      case "\\t"			=> '\u0009' // HT - horizontal tab
      case "\\n"			=> '\u000a' // LF - linefeed
      case "\\f"			=> '\u000c' // FF - form feed
      case "\\r"			=> '\u000d' // CR - carriage return
      case OCTAL(s)   => Integer.parseInt(s, 8).toChar
      case HEX(s)     => Integer.parseInt(s, 16).toChar
      case UNICODE(s) => Integer.parseInt(s, 16).toChar
      case SINGLE(s)  => s(0)
      case _ => throw new InvalidArgument("Single character expected")
    }
  }
  
  // File path parser
  // Converts argument to a java.io.File.
  // ==========================================================================================
  addArgumentParser[File] { arg => new File(arg) }
}

/** Base class of all exceptions thrown by [[org.fud.optparse.OptionParser]].  
 *
 * You should catch this when calling `OptionParser#parse()`. */
class OptionParserException(m: String) extends RuntimeException(m)

/**
 * An instance of this exception should be thrown by argument parsers upon detecting
 * an invalid argument value.
 *
 * See the `addArgumentParser` method of [[org.fud.optparse.OptionParser]]. */
class InvalidArgumentException(m: String) extends OptionParserException(m) { def this() = this("") }

/**
 * An instance of this exception should be thrown by argument parsers upon detecting
 * an ambiguous argument value.
 *
 * See the `addArgumentParser` method of [[org.fud.optparse.OptionParser]]. */
class AmbiguousArgumentException(m: String) extends OptionParserException(m) { def this() = this("") }
