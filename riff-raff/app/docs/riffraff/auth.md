Authentication and Authorisation
================================

Authentication
--------------

Authentication is handled by an Open ID provider.  This defaults to google, but can be overridden in the riff-raff
 properties file.  This obtains the users full name and e-mail address and both of these are used to provide an audit
 trail of deployments and other actions in the app.

Authorisation
-------------

There are a couple of very simple layers of authorisation implemented.

The first layer is a whitelist of e-mail domains.  For example, this can be set up to allow only guardian.co.uk e-mail
addresses to login and prevent users accidentally using their personal accounts.  This is configured in the application
properties file.  When the property is empty, all domains are allowed.

The second layer is a whitelist of e-mail addresses themselves.  This can be set in the properties file as well and
again when empty will allow any user to log in.  However, there is also a property that enables use of the database
to whitelist addresses.  When enabled a menu item will appear allowing any authorised user to add further users to the
list.  These addresses are in addition to the property, although when the database option is enabled an empty property
will no longer allow any user into the system.

When enabling the database setting you'll need to ensure that at least one user is whitelisted to set up the first
users.