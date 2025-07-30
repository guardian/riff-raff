<!--- prev:properties -->
Authentication and Authorisation
================================

Authentication
--------------

Authentication is handled by Google. This obtains the users full name and e-mail address and both of these are used to 
provide an audit trail of deployments and other actions in the app.

Google auth is implemented in two layers
- Load Balancer layer: this component is defined in [the main riff raff cloudformation template](https://github.com/guardian/deploy-tools-platform/blob/main/cloudformation/riffraff/riffraff.template.yaml) and uses [authenticate-oidc](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/listener-authenticate-users.html#configure-user-authentication). It relies on a secret stored in SSM.
- Application layer: this layer uses The Guardian's [Play Google Auth Module](https://github.com/guardian/play-googleauth) and relies on secrets and configuration defined in the main [application.conf](https://github.com/guardian/riff-raff/blob/afb7e602e11acd7a07aae433c74be22976d8a7cd/riff-raff/conf/application.conf#L40-L41) stored in S3.

To rotate these secrets we recommend a workflow that minimises risk by making it easy to perform a rollback until the very last step. At a high level, it consists of creating a new secret while keeping the old one active, switching the application to the new one, and only then disabling and deleting the old secret.

Here is a step-by-step guide, which we recommend following first for the CODE environment and then on PROD:
1. (PROD only) Send a message about this rotation in the DevX Chat channel; this encourages people to report any unexpected problems to us quickly.
1. Create a new Client Secret in [Riff Raff's Google Cloud project](https://console.cloud.google.com/auth/clients?project=guardian-riff-raff) and leave the old secret in place.
1. Patch the application.conf file stored in the deploy tools config S3 bucket to use the new secret.
1. Update `/${STAGE}/deploy/riff-raff/clientSecret` in AWS Secrets Manager. As Secrets Manager is versioned, the new secret will be available as the most recent version with a given id.
1. Update the [secret version id for the relevant stage](https://github.com/guardian/deploy-tools-platform/blob/04591aa388c08c66a71461189c33add0b0cd4aa9/cloudformation/riffraff/riff-raff.yaml#L15-L19). For `CODE` updates, you will need to deploy the `tools::riffraff-cloudformation` project manually. For `PROD` updates, merging your PR will automatically trigger a [deployment to update the infrastructure](https://riffraff.gutools.co.uk/deployment/history?projectName=tools%3A%3Ariffraff-cloudformation&stage=PROD&pageSize=20&page=1).
1. Use Riff Raff to deploy itself, which will re-run the application startup routine (including loading the updated `application.conf`) while keeping the same instance alive. (Note: when it comes to this process we use Riff Raff CODE to deploy CODE, and PROD to deploy PROD.)
1. At this point both layers of authentication should be using the new secret, so you can disable the old credentials in the Google Cloud Project. (Note that GCP does not guarantee that disabling an existing credential is instant. It is unlikely, but it might take up to several hours in the worst case.)
1. Log into Riff Raff via an Incognito window to confirm that logging in (via both layers of auth) still works with the new secret. An issue with the load balancer layer will prevent any of Riff Raff's HTML from being presented, so you might be served a `561 Authentication Error` directly from the load balancer or an error page from Google Auth. An issue at the application layer may show up as an in-application error, such as a red banner saying `Login failure. Internal error, please check logs for more information`.
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
