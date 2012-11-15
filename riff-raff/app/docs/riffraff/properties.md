Properties
==========

Configuration of Riff-Raff is done via the guardian configuration project (see
[the github project](https://github.com/guardian/guardian-configuration)).  This is essentially a hierarchy of
properties files that are picked up when the application starts.

basics
------

 - `urls.publicPrefix` - The public URL prefix used to use when generating URLs in notifications

auth
----

 - `auth.openIdUrl` - configure the authentication OpenID provider
 - `auth.domains` - white list of e-mail address domains to allow access, empty for all
 - `auth.whitelist.addresses` - white list of e-mail addresses to allow access, empty for all
 - `auth.whitelist.useDatabase` - enable database module and in app configuration of e-mail whitelist

credentials
-----------

 - `sshKey.path` - path to the passphrase free SSH key to use during deployments
 - `s3.secretAccessKey.XXXXX` - the secret access key to accompany access key `XXXXX`

continuous integration
----------------------

 - `teamcity.serverURL` - URL to root of teamcity server

database
--------

 - `mongo.uri` - A mongo [standard connection string](http://www.mongodb.org/display/DOCS/Connections)
 - `mongo.collectionPrefix` - Prefix on created collections (allows multiple Riff-Raff instances to use the same mongo database).  Must only contain characters valid in a collection name (letters, underscore, numbers - although not first character).

notifications
-------------

 - `irc.host` - IRC host to connect to
 - `irc.channel` - Channel messages should be sent to
 - `irc.name` - Nick used by Riff-Raff in channel
 - `mq.queueTargets` - List of RabbitMQ targets (comma separated list) to send notifications to in the format of `<server>:<port>/<queueName>`

domains
-------

See documentation in [Riff-raff domains](domains)