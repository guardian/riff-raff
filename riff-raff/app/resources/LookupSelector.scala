package resources

import deployment.DeployInfoManager
import magenta._
import org.joda.time.DateTime
import com.gu.management.DefaultSwitch
import controllers.Logging
import conf.Configuration

object LookupSelector {
  lazy val switches = Seq(enablePrism, enableValidation)

  lazy val enablePrism = new DefaultSwitch(
    "enable-prism-lookup",
    "When on, Riff-Raff will use Prism to lookup instances and data. When off, Riff-Raff will use the in built deployinfo discovery mechanism",
    conf.Configuration.lookup.source match {
      case "deployinfo" => false
      case "prism" => true
      case sourceName => throw new IllegalArgumentException(s"Lookup source $sourceName is not known")
    }
  )
  lazy val enableValidation = new DefaultSwitch(
    "enable-lookup-validation",
    "When on, Riff-Raff will use both Prism and the in built deployinfo discovery mechanism - data will be returned as per the enable-prism-lookup switch, but comparisons will be made and differences recorded in the log",
    conf.Configuration.lookup.validation
  )

  lazy val secretProvider = new SecretProvider {
                              def lookup(service: String, account: String): Option[String] =
                                conf.Configuration.credentials.lookupSecret(service, account)
    override def sshCredentials = SystemUser(keyFile = Configuration.sshKey.file)
  }

  def deployInfoLookup = new Lookup {
    val lookupDelegate = DeployInfoLookupShim(DeployInfoManager.deployInfo, secretProvider)
    val name = "DeployInfo riffraff shim"
    def lastUpdated: DateTime = lookupDelegate.lastUpdated
    def instances: Instances = lookupDelegate.instances
    def data: Data = lookupDelegate.data
    def stages = lookupDelegate.stages.sorted(conf.Configuration.stages.ordering).reverse
    def keyRing(stage: Stage, apps: Set[App], stack: Stack) = lookupDelegate.keyRing(stage, apps, stack)
  }

  def apply():Lookup = {
    val (primary, secondary) = if (enablePrism.isSwitchedOn) {
      (PrismLookup, deployInfoLookup)
    } else {
      (deployInfoLookup, PrismLookup)
    }
    if (enableValidation.isSwitchedOn) LookupValidator(primary,secondary)
    else primary
  }
}

case class LookupValidator(primary:Lookup, validation:Lookup) extends Lookup with Logging {
  val name = s"Validator [Primary: ${primary.name} Validating: ${validation.name}]"

  def lastUpdated: DateTime = primary.lastUpdated

  def compareAndReturn[T](primary:Lookup, validation:Lookup)(diff: Diff[T])(extractor: Lookup => T):T = {
    val primaryInstance = extractor(primary)
    val validationInstance = extractor(validation)
    val priDiff = diff.diff(primaryInstance, validationInstance)
    val valDiff = diff.diff(validationInstance, primaryInstance)
    val same = priDiff.isEmpty && valDiff.isEmpty
    if (same)
      log.info(s"Matching values from (primary: ${primary.name}; validation: ${validation.name})")
    else
      log.warn(
      s"""Lookup return values not equal (primary: ${primary.name}; validation: ${validation.name})
        |In primary but not in validation:
        |$priDiff
        |In validation but not in primary:
        |$valDiff
      """.stripMargin
    )
    primaryInstance
  }
  trait Diff[T] {
    def diff(pri:T, sec:T): Option[T]
  }
  class SeqDiff[T] extends Diff[Seq[T]] {
    def diff(pri: Seq[T], sec: Seq[T]):Option[Seq[T]] = {
      val diff = pri.toSet diff sec.toSet
      if (diff.isEmpty) None else Some(diff.toSeq)
    }
  }
  class OptionDiff[T] extends Diff[Option[T]] {
    def diff(pri: Option[T], sec: Option[T]): Option[Option[T]] = {
      val diff = pri.toSet diff sec.toSet
      if (diff.isEmpty) None else Some(diff.toSeq.headOption)
    }
  }
  object MapDiff extends Diff[Map[String,Seq[Datum]]] {
    val seqDiff = new SeqDiff[String]()
    def flatten(map: Map[String, Seq[Datum]]): Seq[(String,Datum)] = {
      map.toSeq.flatMap{ case (key, values) => values.map(key ->) }
    }
    def diff(pri: Map[String,Seq[Datum]], sec: Map[String,Seq[Datum]]): Option[Map[String,Seq[Datum]]] = {
      val diff = flatten(pri).toSet diff flatten(sec).toSet
      if (diff.isEmpty) None else Some(diff.toSeq.groupBy(_._1).mapValues(_.map(_._2)))
    }
  }
  def lookupWithDiff[T] = compareAndReturn[T](primary, validation) _
  def lookupWithSeqDiff[T] = lookupWithDiff[Seq[T]](new SeqDiff[T]())
  def lookupWithOptionDiff[T] = lookupWithDiff[Option[T]](new OptionDiff[T]())

  def instances: Instances = new Instances {
    def get(pkg: DeploymentPackage, app: App, parameters: DeployParameters, stack: Stack): Seq[Host] =
      lookupWithSeqDiff[Host](_.instances.get(pkg, app, parameters, stack))
    def all: Seq[Host] = lookupWithSeqDiff[Host](_.instances.all)
  }

  def stages: Seq[String] = lookupWithSeqDiff[String](_.stages)

  def data: Data = new Data {
    def datum(key: String, app: App, stage: Stage, stack: Stack): Option[Datum] =
      lookupWithDiff[Option[Datum]](new OptionDiff[Datum]())(_.data.datum(key, app, stage, stack))

    def all: Map[String, Seq[Datum]] = lookupWithDiff[Map[String, Seq[Datum]]](MapDiff)(_.data.all)
    def keys: Seq[String] = lookupWithSeqDiff[String](_.data.keys)
  }

  def keyRing(stage: Stage, apps: Set[App], stack: Stack): KeyRing =
    primary.keyRing(stage, apps, stack)
}