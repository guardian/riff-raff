<!--- prev:index next:auth -->
Properties
==========

Configuration of Riff-Raff is done via the guardian configuration project (see
[the github project](https://github.com/guardian/guardian-configuration)).  This is essentially a hierarchy of
properties files that are picked up when the application starts.

basics
------

 - `urls.publicPrefix` - The public URL prefix used to use when generating URLs in notifications
 - `stages.order` - A comma separated list of stages that override the default alphabetical order of stages shown in the
 web UI, any stages that are not mentioned explicitly will be sorted to the bottom of the list
 - `concurrency.maxDeploys` - The number of deploys that can run at once, once this is reached deploys will be queued in
 a **Waiting** state until another deploy finishes

deployment information
----------------------

 - `lookup.prismUrl` - The url of the Prism endpoint

auth
----

Authentication is designed to use Google's OAuth2 support. You'll need a Client ID which you can obtain from the Google Developers Console at https://console.developers.google.com

 - `auth.clientId` - the OAuth client ID from the Developers Console
 - `auth.clientSecret` - the OAuth client secret from the Developers Console
 - `auth.redirectUrl` - the URL that Google will redirect back to, by default this is worked out from the `url.publicPrefix` so probably doesn't need to be specified
 - `auth.domain` - if specified then then OAuth login will be restricted to this domain
 - `auth.domains` - white list of e-mail address domains to allow access, empty for all
 - `auth.allowlist.addresses` - white list of e-mail addresses to allow access, empty for all
 - `auth.allowlist.useDatabase` - enable database module and in app configuration of e-mail allowlist

credentials
-----------

 - `sshKey.path` - path to the passphrase free SSH key to use during deployments
 - `credentials.<service>.XXXXX` - the secret key to accompany access key `XXXXX` for the named service (e.g. credentials.aws.ACCESSKEY=SECRETACCESSKEY)

continuous deployment
---------------------

 - `continuousDeployment.enabled` - By default continuous deployment is disabled (a message is logged instead of
 starting a deploy - to prevent rogue or development instances of Riff-Raff automatically starting deployments);
 to enable set this property to `true` (you want to do this on your production instance)

artifact
--------

 - `artifact.aws.bucketName` - Bucket to download build artifacts from
 - `artifact.aws.accessKey` - Access key to use when downloading build artifacts. If not present fall back to 
 [DefaultAWSCredentialsProviderChain](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html)
 - `artifact.aws.secretKey` - Secret key to use when downloading build artifacts. If not present fall back to 
 [DefaultAWSCredentialsProviderChain](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html)
 
build
-----

 - `build.aws.bucketName` - Bucket to poll for build.json files describing builds
 - `build.aws.accessKey` - Access key to use when polling for builds. If not present fall back to 
 [DefaultAWSCredentialsProviderChain](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html)
 - `build.aws.secretKey` - Secret key to use when polling for builds. If not present fall back to 
 [DefaultAWSCredentialsProviderChain](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html) 
 - `build.pollingPeriodSeconds` - Number of seconds between incremental updates of available builds from S3 bucket
 
tag
---

 - `tag.aws.bucketName` - Bucket to download tag details from
 - `tag.aws.accessKey` - Access key to use when downloading tag details. If not present fall back to 
 [DefaultAWSCredentialsProviderChain](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html)
 - `tag.aws.secretKey` - Secret key to use when downloading tag details. If not present fall back to 
 [DefaultAWSCredentialsProviderChain](https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html)


database
--------

 - `db.default.url` - A postgres database URL
 - `db.default.user` - A username with enough permissions to manipulate the database
 - `db.default.password` - The user's password

freeze
------

Configure a change freeze (this disables deploys via the API, continuous deploys and presents a warning before
letting uses deploy via the web interface).

 - `freeze.startDate` - A ISO-8601 format date indicating the start of a change freeze
 - `freeze.endDate` - A ISO-8601 format date indicating the start of a change freeze
 - `freeze.stages` - The stages that should be considered as frozen (comma separated)
 - `freeze.message` - A message to be displayed to users being warned that they are in a change freeze

notifications
-------------

 - `irc.host` - IRC host to connect to
 - `irc.channel` - Channel messages should be sent to
 - `irc.name` - Nick used by Riff-Raff in channel
 - `notifications.aws.topicArn` - The ARN of the AWS SNS topic to publish notifications to
 - `notifications.aws.topicRegion` - If you are not posting to a us-east-1 topic you must specify the region here
 - `notifications.aws.accessKey` - the access key for publishing to the topic
 - `notifications.aws.secretKey` - the secret key for publishing to the topic
