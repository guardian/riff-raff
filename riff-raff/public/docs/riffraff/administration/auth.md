<!--- prev:properties next:move -->
Authentication and Authorisation
================================

Authentication
--------------

Authentication is handled by Google. This obtains the users full name and e-mail address and both of these are used to 
provide an audit trail of deployments and other actions in the app.

Google auth is implemented in two layers
- Load Balancer layer: this component is defined in the main riff raff cloudformation template and uses [authenticate-oidc](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/listener-authenticate-users.html#configure-user-authentication). The template relies on a secret stored in SSM.
- Application layer: this layer uses The Guardian's [Play Google Auth Module](https://github.com/guardian/play-googleauth) and is configured in the main [application.conf](https://github.com/guardian/riff-raff/blob/afb7e602e11acd7a07aae433c74be22976d8a7cd/riff-raff/conf/application.conf#L40-L41).

To rotate the secrets, follow these steps:
1. Create a new Client Secret in Riff Raff's Google Cloud project
2. Patch the PROD and CODE application.conf to use the new secret
3. Update the secret in AWS Secrets Manager used by the cloudformation template.
4. Use riff raff to deploy itself, which will re-run the application startup routine while keeping the same instance alive
5. Disable the old credentials in the Google Cloud Project
6. Wait for at least 2 or 3 hours to confirm the old credentials are no longer being used, as the turn around time for disabling credentials is slow and not deterministic
7. Delete the old credentials in Riff Raff's Google Cloud project

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
