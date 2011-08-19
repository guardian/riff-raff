package deploy

import org.fud.optparse.{OptionParserException, OptionParser}
import com.gu.deploy.Config

object ProcessTest {
//  startAProcess()
//
//  def startAProcess() {
//    import sys.process._
//    println("Running ls..")
//
//   val cmd = "ls" #| List("xargs", "cat")
//    println(cmd)
//    cmd.!!
//
//    cmd !
    //println("Run ls returned " + retCode)

    //val retVal = List("ls", "-l").!!
//  }

  def main(args: Array[String]) {
    val c = Config.fromCmdLine(args)

    println(c)
  }

}