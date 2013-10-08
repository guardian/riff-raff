Domains
=======

The purpose of riff-raff domains is to facilitate deployment responsibility.

At the Guardian, the network is setup such that we have deployment hosts specifically for deploying to our pre-PROD
 environments and another set of hosts specifically for PROD.  Firewalls and routing prevents deploys to PROD from
 the pre-PROD deploy host and pre-PROD from PROD.

Domains are used in this instance is nominating hosts to deploy software to different stages and the simplest is a PROD
and pre-PROD split which is discussed below.

Given a deploy host that can deploy to PROD (prod-deploy.foo.com) and one that deploy pre-PROD (preprod-deploy.foo.com)
 you can create a configuration section in riff-raff.properties that looks like this:

    domains.enabled=true
    domains.prod-deploy.responsibility.stage.regex=^PROD$
    domains.prod-deploy.urlPrefix=https://prod-deploy.foo.com
    domains.preprod-deploy.responsibility.stage.regex=^PROD$
    domains.preprod-deploy.responsibility.stage.invertRegex=true
    domains.preprod-deploy.urlPrefix=https://preprod-deploy.foo.com

This creates two domains, one named prod-deploy and the other preprod-deploy.  The prod-deploy domain is responsible for
deploying software to stages that match the regex ^PROD$ (only the exact string PROD in this case).  The second domain
is simply configured with the same regex but with the invertRegex flag set to true (i.e. the node is responsible
for any stages that do NOT match the regex).

When an interactive deploy is attempted on a node that does not have responsibility for it, the user is redirected to
a domain that can handle it using the urlPrefix to locate the other riff-raff installation.

The configuration above must match on all domains.  Provided the name given matches the hostname, it will identify
itself automatically, but if "No domain configuration for this node" errors are encountered you can add a further
setting:

    domains.identity=prod-deploy
