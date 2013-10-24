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

auth
----

 - `auth.openIdUrl` - configure the authentication OpenID provider
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
 - `teamcity.tagging.pollingWindowMinutes` - Continuous deploys triggered by a new tag will only pick up new tags on builds that are not older than this value - defaults to 10 days

database
--------

 - `mongo.uri` - A mongo [standard connection string](http://www.mongodb.org/display/DOCS/Connections)
 - `mongo.collectionPrefix` - Prefix on created collections (allows multiple Riff-Raff instances to use the same mongo database).  Must only contain characters valid in a collection name (letters, underscore, numbers - although not first character).

notifications
-------------

 - `irc.host` - IRC host to connect to
 - `irc.channel` - Channel messages should be sent to
 - `irc.name` - Nick used by Riff-Raff in channel
 - `alerta.endpoints` - List of [alerta](https://github.com/guardian/alerta) API endpoints that should be notified of deployment start, complete and fail events

domains
-------

See documentation in [Riff-raff domains](domains)