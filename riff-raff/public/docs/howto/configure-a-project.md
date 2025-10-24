Configuring a project
=====================

To make a project deployable with Riff-Raff the following files need to be uploaded into Riff-Raff's S3 buckets:
 
 - Any artifacts for your project (e.g. a .deb file for your application and a CloudFormation template for infrastructure)
 - A [`riff-raff.yaml`](../reference/riff-raff.yaml.md) file that describes the deployment process
 - A [`build.json`](../reference/build.json.md) file that details the CI build

[@guardian/cdk](https://github.com/guardian/cdk) can be used to generate the `riff-raff.yaml` file automatically as
part of your build. See https://github.com/guardian/cdk/tree/main/src/riff-raff-yaml-file#usage for more details.

[actions-riff-raff](https://github.com/guardian/actions-riff-raff) will create the
`build.json` file for you and help you to upload the files to Riff-Raff's S3 buckets correctly.

Setting up a new project
------------------------

 1. Ensure that you are [using `GuRoot`](https://github.com/guardian/cdk/tree/main/src/riff-raff-yaml-file#usage) when 
    instantiating your `cdk` stacks; this will produce a `riff-raff.yaml` file for you alongside CloudFormation templates
    for the relevant stages.
 1. Configure [actions-riff-raff](https://github.com/guardian/actions-riff-raff) to upload your CloudFormation templates
    and application build artifact as part of your CI build. This needs to happen _after_ you've run the `cdk synth`
    command and built the application artifact.
 1. For most projects, you will also want to configure continuous deployment (CD) for the `main` branch in `PROD`.