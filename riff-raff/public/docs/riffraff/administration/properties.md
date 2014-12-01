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

 - `deployinfo.location`
 - `deployinfo.mode` - How to interpret the location:
    - `URL` will interpret it as a URL holding the JSON data (using classpath: as the protocol will resolve something on the classpath).
    - `Execute` will interpret it as a local executable which will return JSON on stdout.
 - `deployinfo.refreshSeconds` - The number of seconds between attempts to update the deployment information
 - `deployinfo.timeoutSeconds` - When in `Execute` mode this will give up and attempt to kill the process if it hasn't exited after this number of seconds.
 - `lookup.prismUrl` - The url of the Prism endpoint
 - `lookup.source` - Whether to use the internal `deployinfo` or external `prism` endpoint
 - `lookup.staleMinutes` - The age of lookup information in minutes that we consider to be stale
 - `lookup.validation` - When `true`, query non-active backend and compare the results with the active backend - logging any differences

auth
----

Authentication is designed to use Google's OAuth2 support. You'll need a Client ID which you can obtain from the Google Developers Console at https://console.developers.google.com

 - `auth.clientId` - the OAuth client ID from the Developers Console
 - `auth.clientSecret` - the OAuth client secret from the Developers Console
 - `auth.redirectUrl` - the URL that Google will redirect back to, by default this is worked out from the `url.publicPrefix` so probably doesn't need to be specified
 - `auth.domain` - if specified then then OAuth login will be restricted to this domain
 - `auth.domains` - white list of e-mail address domains to allow access, empty for all
 - `auth.whitelist.addresses` - white list of e-mail addresses to allow access, empty for all
 - `auth.whitelist.useDatabase` - enable database module and in app configuration of e-mail whitelist

credentials
-----------

 - `sshKey.path` - path to the passphrase free SSH key to use during deployments
 - `credentials.<service>.XXXXX` - the secret key to accompany access key `XXXXX` for the named service (e.g. credentials.aws.ACCESSKEY=SECRETACCESSKEY)

continuous deployment
---------------------

 - `continuousDeployment.enabled` - By default continuous deployment is disabled (a message is logged instead of
 starting a deploy - to prevent rogue or development instances of Riff-Raff automatically starting deployments);
 to enable set this property to `true` (you want to do this on your production instance)

continuous integration
----------------------

 - `teamcity.serverURL` - URL to root of teamcity server
 - `teamcity.user` - User name to authenticate against TeamCity - if not specified guest authentication will be used
 - `teamcity.password` - Password for the specified user - if not specified guest authentication will be used
 - `teamcity.pinSuccessfulDeploys` - Set to `true` if Riff Raff should pin builds after a successful deploy
 - `teamcity.pinStages` - Comma separated list of stages that limits which deploys will result in the artifact being pinned
 - `teamcity.maximumPinsPerProject` - The number of pins that should be retained, when there are more than this number pinned then older pins will be removed
 - `teamcity.pollingWindowMinutes` - Set this to be over the length of the longest running builds.  Used by the incremental updater to be smart about what it asks for.
 - `teamcity.pollingPeriodSeconds` - Number of seconds between incremental updates of TeamCity builds
 - `teamcity.fullUpdatePeriodSeconds` - Number of seconds between full updates of TeamCity builds (includes new projects and configurations)

database
--------

 - `mongo.uri` - A mongo [standard connection string](http://www.mongodb.org/display/DOCS/Connections)
 - `mongo.collectionPrefix` - Prefix on created collections (allows multiple Riff-Raff instances to use the same mongo database).  Must only contain characters valid in a collection name (letters, underscore, numbers - although not first character).

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
 - `alerta.endpoints` - List of [alerta](https://github.com/guardian/alerta) API endpoints that should be notified of deployment start, complete and fail events
 - `notifications.aws.topicArn` - The ARN of the AWS SNS topic to publish notifications to
 - `notifications.aws.topicRegion` - If you are not posting to a us-east-1 topic you must specify the region here
 - `notifications.aws.accessKey` - the access key for publishing to the topic
 - `notifications.aws.secretKey` - the secret key for publishing to the topic