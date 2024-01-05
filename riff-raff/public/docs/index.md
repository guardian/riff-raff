Riff-Raff
=========

How-to
------

 - [Fix a failed deploy](howto/fix-a-failed-deploy.md) - riff-raff fails fast, here are the basic steps
   for fixing an auto-scaling deploy
 - [Configure a project](howto/configure-a-project.md) - how to set up a project so it can be deployed by Riff-Raff

Reference
---------

 - [riff-raff.yaml](reference/riff-raff.yaml.md) - the main configuration file
 - [artifact layout](reference/s3-artifact-layout.md) - how files should be laid out in the artifact S3 buckets

Using Riff-Raff
---------------

 - [Advanced deployment settings](riffraff/advanced-settings.md) - what is an update strategy and how to choose one
 - [Continuous Integration and Deployment](riffraff/hooksAndCD.md) - guide to the Riff-Raff hooks available to make continuous
 deployment easy
 - [External deploy requests](riffraff/externalRequest.md) - how to help a user start a deploy
 - [AWS S3 bucket configuration for uploads](riffraff/s3buckets.md)
 - [Administration](riffraff/administration/) - details on how to configure Riff-Raff
 - [Restrictions](riffraff/restrictions.md) - how to restrict deployments

Riff-Raff not picking up builds?
---------------
 - Usually, redeploying Riff-Raff fixes this; we use Riff-Raff itself to deploy itself 🎉. The deploy will complete immediately  but riffraff will only redeploy itself when there are no other running deploys. The last deploy time is shown in the bottom right of this page. 
