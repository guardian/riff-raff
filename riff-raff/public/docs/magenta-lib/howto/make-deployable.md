<!--- next:cloud-deployable -->
How do I make my project deployable?
================

**NOTE:** This is out of date and does not accurately reflect the current state of Riff-Raff. In particular 
`artifacts.zip` and `deploy.json` have been deprecated and the user input for `riff-raff.yaml` is different.

Magenta works out how to deploy an application via convention and a little bit
of configuration.  It expects to be able to download an `artifact.zip` from the
Team City server.  That `artifact.zip` must contain within it a file and
directory structure that matches.  The file must be called `deploy.json` and
available in the root of the `artifact.zip` The rest of the directory must be
laid out as follows:

    /
    /deploy.json
    /packages
    /packages/<package_name>/*

Inside the deploy.json you specify what packages make up your application.  This
is defined in the packages dictionary:

    "packages": {
      "demo1": {
        "type": "jetty-webapp",
        "data": {
          "port": "8700"
        }
      }
    },

Each package has a name, a type, a list of apps and any data to overide the
type.

* Name:

  This is the name of the package, it will be used later on in the deploy.json
  to refer to this package

* Type:

  The type of deployment.  The various deployment types define what the deployable
  actions are for this package.  For example the `jetty-webapp` type, we block
  the container, copy the files, restart the container, test the ports, test a
  set of urls and unblock.

* Data:

  Overridable data for the package-type.  Each deployment type might have specific
  data, for example, at the guardian we might run multiple jetty-webapp
  instances on the same server, and so the packages can override the port that
  it expects the server to be listening on when it has finished restarting.

* Apps:

  The sort of machine to deploy to. The systems machine interface will return
  a list of hosts with compatible apps, so this must match the responses from
  your operations teams deployment info scripts. Defaults to a single app with
  the same name as your package.

Each package should come with supporting files in the
`/packages/<package_name>` directory.  For jetty-webapps we simply copy the
entire directory into `/jetty-apps/<package_name>/` meaning that your war
should be in `/packages/<package_name>/webapps/<package_name>.war`

After the package definition, you may define some deployment recipes.
These are essentially types of deployment that you might need to do.  If the
deployer does not specify a recipe to execute, the recipe called `default` will
be executed.

     "recipes": {
       "default": {
         "actionsPerHost": [
           "demo1.deploy"
         ]
       }
     }

Each recipe contains a list of actions that will be executed in order.  The
actions are defined by the type that the package uses.  If no recipes are specified
the default recipe will be assumed to be to execute the deploy action for all
packages.