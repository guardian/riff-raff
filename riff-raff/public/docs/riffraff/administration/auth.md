<!--- prev:properties next:move -->
Authentication and Authorisation
================================

Authentication
--------------

Authentication is handled by Google. This obtains the users full name and e-mail address and both of these are used to 
provide an audit trail of deployments and other actions in the app.

Google auth is implemented in two layers
- Load Balancer layer: this component is defined in [the main riff raff cloudformation template](https://github.com/guardian/deploy-tools-platform/blob/main/cloudformation/riffraff/riffraff.template.yaml) and uses [authenticate-oidc](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/listener-authenticate-users.html#configure-user-authentication). The template relies on a secret stored in SSM.
- Application layer: this layer uses The Guardian's [Play Google Auth Module](https://github.com/guardian/play-googleauth) and is configured in the main [application.conf](https://github.com/guardian/riff-raff/blob/afb7e602e11acd7a07aae433c74be22976d8a7cd/riff-raff/conf/application.conf#L40-L41).

To rotate the secrets, follow these steps:
1. Create a new Client Secret in Riff Raff's Google Cloud project.
1. Patch the PROD and CODE application.conf to use the new secret.
1. Update the `/INFRA/deploy/riff-raff/clientSecret` secret in AWS Secrets Manager.
1. Update the [secret version id used in the CloudFormation template](https://github.com/guardian/deploy-tools-platform/blob/11730ea2841926148e98ab45e6d118bd1a133d27/cloudformation/riffraff/riffraff.template.yaml#L592). Merging your PR will trigger a [deployment to update the infrastructure](https://riffraff.gutools.co.uk/deployment/history?projectName=tools%3A%3Ariffraff-cloudformation&stage=PROD&pageSize=20&page=1).
1. Use riff raff to deploy itself, which will re-run the application startup routine (including loading the updated `application.conf`) while keeping the same instance alive.
1. Disable the old credentials in the Google Cloud Project.
1. Log into Riff-Raff via an Incognito window to confirm that logging in (via both layers of auth) still works with the new secret.
1. Delete the old credentials in Riff Raff's Google Cloud project.

Authorisation
-------------

There are a couple of very simple layers of authorisation implemented.

The first layer is an allowlist of e-mail domains.  For example, this can be set up to allow only guardian.co.uk e-mail
addresses to login and prevent users accidentally using their personal accounts.  This is configured in the application
properties file.  When the property is empty, all domains are allowed.

The second layer is an allowlist of e-mail addresses themselves.  This can be set in the properties file as well and, as
with the domain allowlist, will allow any user to log in when empty.

For more flexible authorisation however, it is also possible to use the database to allowlist addresses.  When enabled
via the auth.allowlist.useDatabase property, a menu item will appear allowing any authorised user to add
further users to the list.  These addresses are in addition to the property, although when the database option is
enabled an empty property will no longer allow any user into the system.

When enabling the database setting you'll need to ensure that at least one user is allowlisted to set up the first
users.
