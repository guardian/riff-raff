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

### Legacy 

You should never use these:
 
 - [artifact.zip](reference/artifacts.zip.md)
 - [deploy.json](reference/deploy.json.md)
 

Using Riff-Raff
---------------

 - [API](riffraff/api.md) - how to use the API and a description of the endpoints available
 - [Continuous Integration and Deployment](riffraff/hooksAndCD.md) - guide to the Riff-Raff hooks available to make continuous
 deployment easy
 - [External deploy requests](riffraff/externalRequest.md) - how to help a user start a deploy
 - [AWS S3 bucket configuration for uploads](riffraff/s3buckets.md)
 - [Administration](riffraff/administration/) - details on how to configure Riff-Raff
 
Legacy documentation
--------------------

Some docs that have not been reviewed or updated recently but might (at a stretch) still have some useful 
information (or more likely history) in them:

 - [How magenta works](magenta-lib/concepts/how-magenta-works.md)
 - [Terminology](magenta-lib/concepts/terminology.md)
 - [How to deploy to the cloud](magenta-lib/howto/cloud-deployable.md)
 - [How do I make my project deployable?](magenta-lib/howto/make-deployable.md)