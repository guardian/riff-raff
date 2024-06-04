package magenta.deployment_type

import magenta.{DeployReporter, DeployTarget, DeploymentPackage}
import play.api.libs.json.{JsValue, Json, Reads}

import java.time.Duration

trait ParamRegister {
  def add(param: Param[_]): Unit
}

/** A parameter for a deployment type
  *
  * @param name
  *   The name of the parameter that should be extracted from the parameter map
  *   (riff-raff.yaml) or data map (deploy.json)
  * @param documentation
  *   A documentation string (in markdown) that describes this parameter
  * @param optional
  *   This can be set to true to make the parameter optional even when there are
  *   no defaults. This might be needed if the value is not required to have a
  *   default or when only one of two different parameters are specified.
  * @param defaultValue
  *   The default value for this parameter - used when a value is not found in
  *   the map
  * @param defaultValueFromContext
  *   A function that returns a default value for this parameter based on the
  *   package for this deployment
  * @param register
  *   The parameter register - a Param self registers
  * @tparam T
  *   The type of the parameter to extract
  */
case class Param[T](
    name: String,
    documentation: String = "_undocumented_",
    optional: Boolean = false,
    defaultValue: Option[T] = None,
    defaultValueFromContext: Option[
      (DeploymentPackage, DeployTarget) => Either[String, T]
    ] = None,
    deprecatedDefault: Boolean = false
)(implicit register: ParamRegister, reads: Reads[T], manifest: Manifest[T]) {
  register.add(this)

  val required =
    !optional && defaultValue.isEmpty && defaultValueFromContext.isEmpty

  def get(pkg: DeploymentPackage): Option[T] =
    pkg.pkgSpecificData
      .get(name)
      .flatMap(jsValue => parse(jsValue))

  def parse(jsValue: JsValue): Option[T] = Json.fromJson[T](jsValue).asOpt

  def apply(
      pkg: DeploymentPackage,
      target: DeployTarget,
      reporter: DeployReporter
  ): T = {
    val maybeValue = get(pkg)
    val defaultFromContext = defaultValueFromContext.map(_(pkg, target))

    val maybeDefault =
      defaultValue.orElse(defaultFromContext.flatMap(_.right.toOption))
    (maybeDefault, maybeValue) match {
      // the bucket checks below are to aid migrating to this being a required field as we are simply guessing otherwise
      case (Some(default), Some(value))
          if default == value && !deprecatedDefault =>
        reporter.info(
          s"Parameter $name is unnecessarily explicitly set to the default value of $default"
        )
      case (Some(_), None) if deprecatedDefault =>
        reporter.warning(
          s"Parameter $name must always be explicitly set (the default is deprecated as it is quite magical™)"
        )
      case _ => // otherwise do nothing
    }

    (maybeValue, defaultValue, defaultFromContext) match {
      case (Some(userDefined), _, _)           => userDefined
      case (_, Some(default), _)               => default
      case (_, _, Some(Right(contextDefault))) => contextDefault
      case (_, _, Some(Left(contextError))) =>
        throw new NoSuchElementException(
          s"Error whilst generating default for parameter $name in package ${pkg.name} [${pkg.deploymentType.name}]: $contextError"
        )
      case _ =>
        throw new NoSuchElementException(
          s"Package ${pkg.name} [${pkg.deploymentType.name}] requires parameter $name of type ${manifest.runtimeClass.getSimpleName}"
        )
    }
  }

  def default(default: T) = {
    this.copy(defaultValue = Some(default))
  }
  def defaultFromContext(
      defaultFromContext: (DeploymentPackage, DeployTarget) => Either[String, T]
  ) = {
    this.copy(defaultValueFromContext = Some(defaultFromContext))
  }
}

object Param {
  private val ReadIntAsSeconds: Reads[Duration] =
    Reads.of[Int].map(Duration.ofSeconds(_))

  /** Create a parameter that represents a number of seconds to wait for
    * something. Riff Raff has many existing parameters for setting durations
    * where the values are *expected* to be in seconds, so this helper method
    * makes that clear and ensures that the value is explicitly parsed as a
    * duration in *seconds*.
    */
  def waitingSecondsFor(name: String, waitingOn: String)(implicit
      register: ParamRegister
  ): Param[Duration] =
    Param[Duration](name, s"Number of seconds to wait for $waitingOn")(
      register,
      ReadIntAsSeconds,
      implicitly[Manifest[Duration]]
    )
}
