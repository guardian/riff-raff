The riff-raff.yaml configuration file
=====================================

If you are using [@guardian/cdk](https://github.com/guardian/cdk) this file can now be generated automatically as
part of your build. See https://github.com/guardian/cdk/tree/main/src/riff-raff-yaml-file#usage for more details.

If you need to write or edit riff-raff.yaml files by hand you are strongly advised to use the
[Validate Template feature](/configuration/validation) in Riff-Raff to get fast feedback.

Here is some more information about the structure of this configuration file:

```yaml
stacks:
  - String
regions:
  - String
allowedStages:
  - String
templates:
  map of DeploymentOrTemplate
deployments:
  map of DeploymentOrTemplate
```

### stacks

List of default stack names used to identify resources for this deploy. See `stacks` in `DeploymentOrTemplate`.

_Required:_ Conditional. A list of stacks must be provided for each deployment. This provides a default that will be
used by a deployment when a list of stacks are not specified explicitly in the deployment or a template it inherits. If
stacks are specified elsewhere then this is not needed.

### regions

List of default AWS region names that this should be deployed to. See `regions` in `DeploymentOrTemplate`.

_Required:_ Conditional. A list of regions must be provided for each deployment. This provides a default that will be
used by a deployment when a list of regions are not specified explicitly in the deployment or a template it inherits. If
regions are specified elsewhere then this is not needed.

### allowedStages

Optional list of permitted stages for the deployment. Typically used to prevent
deployment to unsupported stages and also used to narrow the stage options for
manual deployments.

### templates

Map of named `DeploymentOrTemplate` objects. Each object can be inherited by a deployment, or another template and they
will be merged together when the file is parsed.

### deployments

Map of named DeploymentOrTemplate objects. Keys in this map represent deployment names, and by default Riff-Raff will
attempt to load deployment content from a directory adjacent to riff-raff.yaml with the same name as the deployment.
Changing the `contentDirectory` property overrides this default behaviour.

The values in the map are  `DeploymentOrTemplate` objects, each representing a deployment step.

DeploymentOrTemplate
--------------------
```yaml
type: String
template: String
stacks:
  - String
regions:
  - String
actions:
  - String
app:
  - String
contentDirectory:
  - String
dependencies:
  - String
parameters:
  map of any
```

### type

The deployment type for this deployment step. You can see the available deployment types and the related actions and
parameters [here](../magenta-lib/types.md).

_Required:_ Conditional. One of `type` or `template` must be specified.

### template

The name of a template in the templates section from which to use as a starting point. The values of the fields in the
template will be used by default. Any values from the template can be overridden or supplemented (in the case of
`parameters`).

_Required:_ Conditional. One of `type` or `template` must be specified.

### stacks

A stack name is used to lookup AWS credentials (effectively selecting which AWS account to deploy to), to locate
auto scaling groups and cloudformation stacks and also to set sane defaults for many parameters.

_Required:_ Conditional. A list of stacks must be provided for each deployment. If specified here it will override any
global default.

### regions

List of default AWS region names that this should be deployed to. This must be a valid [AWS region code](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-regions-availability-zones.html?shortFooter=true#concepts-available-regions).
This is used to create AWS SDK clients before undertaking deployment operations.

_Required:_ Conditional. A list of regions must be provided for each deployment. If specified here it will override any
global default.

### actions

List of deployment type actions to execute for this deployment. See the [deployment types list](../magenta-lib/types.md)
for the available actions of each type.

_Required:_ No. If not specified then the default set of actions will be executed according to the deployment type.

### app

The app name used when looking up AWS credentials, auto scaling groups and cloudformation stacks by tag.

_Required:_ No. If not specified then this will default to the name of the deployment (the key in the `deployments` map).

### contentDirectory

The name of the directory in which the assets for this deployment can be found. See the
[artifact layout](s3-artifact-layout.md) reference for more information.

_Required:_ No. If not specified then this will default to the name of the deployment (the key in the `deployments` map).

### dependencies

The name of any deployments (the key in the `deployments` map) that must be completed before this deployment will run.

_Required:_ No.

### parameters

Map of parameters for the deployment type. This can be used to specify various options that control how a deployment
type should behave. Some examples are bucket names, MIME type mappings, cache control headers and deployment wait times.

_Required:_ Conditional. Some deployment types have parameters which must be specified. See the
[deployment types list](../magenta-lib/types.md) for the available and required parameters of each type.

Examples
--------

In order to understand how Riff-Raff interprets these files feel free to copy the YAML and paste it into Validate
Template page in Riff-Raff.

Here is an example of the configuration for an EC2-based application, where Riff-Raff is responsible for deploying
the infrastructure (via CloudFormation) as well as application code changes:

```yaml
stacks:
  - deploy
regions:
  - eu-west-1
deployments:
  amigo:
    type: autoscaling
    parameters:
    dependencies:
      - cloudformation
  cloudformation:
    type: cloud-formation
    app: amigo
    parameters:
      amiTags:
        Recipe: arm64-jammy-java21-deploy-infrastructure
        AmigoStage: PROD
      amiEncrypted: true
      templateStagePaths:
        CODE: AMIgo-CODE.template.json
        PROD: AMIgo-PROD.template.json
      amiParameter: AMIAmigo
```

This is an example of how templates and overrides can be used. This example also uses the JSON notation
for the arrays.

```yaml
regions: [eu-west-1]
stacks: [flexible, flexible-secondary]

templates:
  flexible:
    type: autoscaling

deployments:
  api:
    template: flexible
  composer-backend:
    template: flexible
  integration:
    template: flexible
    stacks: [flexible]
```