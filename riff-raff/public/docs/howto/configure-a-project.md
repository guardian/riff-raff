Configuring a project
=====================

To make a project deployable with Riff-Raff it needs:
 
 - A [`riff-raff.yaml`](../reference/riff-raff.yaml.md) file that describes the deployment process
 - A [`build.json`](../reference/build.json.md) file that details the CI build
 - The two files above along with any assets uploaded into Riff-Raff's S3 artifact buckets

[@guardian/cdk](https://github.com/guardian/cdk) can be used to generated the `riff-raff.yaml` file automatically as
part of your build. See https://github.com/guardian/cdk/tree/main/src/riff-raff-yaml-file#usage for more details.

[`actions-riff-raff`](https://github.com/guardian/actions-riff-raff) will create the
`build.json` file for you and help you to upload the files to the S3 buckets correctly.

Using SBT
---------

 1. Add [sbt-riffraff-artifact plugin](https://github.com/guardian/sbt-riffraff-artifact) to your project (see the
    docs in the repo)
 1. Ensure there is a `riff-raff.yaml` either in the base directory of your project or in a resources directory (`conf/` 
    for a play app)
 1. Set up your CI server to run the appropriate task (this is correct as of version 0.9.x of the plugin, check the 
 docs on the plugin repo if you are using a later version):
     a. If using TeamCity with the [S3 plugin](https://github.com/guardian/teamcity-s3-plugin) then you can choose to use 
        the `riffRaffNotifyTeamcity` task
     b. Otherwise, or if you are not sure, you should use the `riffRaffUpload` SBT task - you'll need to make sure that 
        the `riffRaffUploadArtifactBucket` and `riffRaffUploadManifestBucket` settings are configured in your SBT file
 1. It is recommended to set the `riffRaffManifestProjectName` - this is the name that you type into Riff-Raff when
    starting a deployment
    
Using an autoscaling deployment type
------------------------------------

The most common deployment uses the `autoscaling` deployment type. This uploads your artifacts to an S3 bucket,
doubles the size of an autoscaling group (the new instances download the new artifact on boot), waits for the new
instances to come into service and then terminates the old instances. For more details see the [autoscaling deployment
type docs](../magenta-lib/types#autoscaling)

In order to make this work you need:

 1. An autoscaling group tagged with the stack, app and stage you wish to deploy to
 1. A launch configuration that will download the artifact from the S3 bucket
 1. A riff-raff.yaml file containing:
     a. the regions and stacks that you want to deploy to (the stack is important - it must match the tags on the AWS 
        account credentials you wish to use and the stack tags on your autoscaling group)
     a. a deployment of type `autoscaling` and an S3 target bucket location defined. By default Riff-Raff will use the value in the SSM parameter `/account/services/artifact.bucket`. This can be customised with `bucketSsmKey` if necessary. See https://riffraff.gutools.co.uk/docs/magenta-lib/types#autoscaling for full detail.
