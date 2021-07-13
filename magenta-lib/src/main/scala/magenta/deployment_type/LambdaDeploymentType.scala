package magenta.deployment_type

trait LambdaDeploymentType extends DeploymentType with BucketParameters  {
  def functionNamesParamDescriptionSuffix: String
  val functionNamesParam = Param[List[String]]("functionNames",
    s"""One or more function names to $functionNamesParamDescriptionSuffix.
      |Each function name will be suffixed with the stage, e.g. MyFunction- becomes MyFunction-CODE""".stripMargin,
    optional = true
  )
  // TODO: make 'deploy' an injected value that describes the deployment type x 3
  val lookupByTags = Param[Boolean]("lookupByTags",
    """When true, this will lookup the function to deploy to by using the Stack, Stage and App tags on a function.
      |The values looked up come from the `stacks` and `app` in the riff-raff.yaml and the stage deployed to.
    """.stripMargin
  ).default(false)

  val prefixStackParam = Param[Boolean]("prefixStack",
    "If true then the values in the functionNames param will be prefixed with the name of the stack being deployed").default(true)

  val prefixStackToKeyParam = Param[Boolean]("prefixStackToKey",
    documentation = "Whether to prefix `package` to the S3 location"
  ).default(true)

  val functionsParam = Param[Map[String, Map[String, String]]]("functions",
    documentation =
      """
        |In order for this to work, magenta must have credentials that are able to perform `lambda:UpdateFunctionCode`
        |on the specified resources.
        |
        |Map of Stage to Lambda functions. `name` is the Lambda `FunctionName`. The `filename` field is optional and if
        |not specified defaults to `lambda.zip`
        |e.g.
        |
        |        "functions": {
        |          "CODE": {
        |           "name": "myLambda-CODE",
        |           "filename": "myLambda-CODE.zip",
        |          },
        |          "PROD": {
        |           "name": "myLambda-PROD",
        |           "filename": "myLambda-PROD.zip",
        |          }
        |        }
      """.stripMargin,
    optional = true
  )
}
