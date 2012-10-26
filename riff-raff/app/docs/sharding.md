Sharding Riff-raff
==================

The purpose of sharding riff-raff is not sharding of the backend data, but rather sharding of deployment responsibility.

At the Guardian, the network is setup such that we have deployment hosts specifically for deploying to our pre-PROD
 environments and another set of hosts specifically for PROD.  Firewalls and routing prevents deploys to PROD from
 the pre-PROD deploy host and pre-PROD from PROD.

Sharding in this instance is nominating hosts to deploy software to different stages and the simplest is a PROD and
anything else split which is discussed below.

Given a deploy host that can deploy to PROD (prod-deploy.foo.com) and one that deploy pre-PROD (preprod-deploy.foo.com)
 you can create a configuration section in riff-raff.properties that looks like this:

    sharding.enabled=true
    sharding.prod-deploy.responsibility.stage.regex=^PROD$
    sharding.prod-deploy.urlPrefix=https://prod-deploy.foo.com
    sharding.preprod-deploy.responsibility.stage.regex=^PROD$
    sharding.preprod-deploy.responsibility.stage.invertRegex=true
    sharding.preprod-deploy.urlPrefix=https://preprod-deploy.foo.com

This creates two shards, one named prod-deploy and the other preprod-deploy.  The prod-deploy shard is responsible for
deploying software to stages that match the regex ^PROD$ (only the exact string PROD in this case).  The second shard
is simply configured with the same regex but with the invertRegex flag set to true (i.e. the node is responsible
for any stages that do NOT match the regex).

When an interactive deploy is attempted on a node that does not have responsibility for it, the user is redirected to
a shard that can handle it using the urlPrefix to locate the other riff-raff installation.

The configuration above must match on all shards.  Provided the name given matches the hostname, it will identify itself
automatically, but if "No shard configuration for this node" errors are encountered you can add a further setting:

    sharding.identity=prod-deploy
