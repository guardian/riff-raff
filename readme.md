Magenta
=======

"Deploy the transit beam"

About
-----

The guardians new scala based deployment library is designed to help automate
deploys by localising knowledge about how to orchestrate deploys within a
single system, but having defined interfaces with the applications and the
system on to which the deploys will happen.

I'm a system administrator, how do I deploy something?
---------------------

Magenta is responsible for locating the depoyable artifacts, calculating the
hosts to deploy onto and executing the deployment steps.  This should make your
job fairly easy.  Assuming that a build exists in Team City, you simply do:

    $ java -jar magenta.jar <stage> <project-name> <build>

e.g.

    $ java -jar magenta.jar PROD ContentApi::master 122
    $ java -jar magenta.jar TEST "Test Product::first build" 1

Magenta should do the rest.

If you are of a nervous disposition and want to dry-run the deploy, finding out
what it would do but not actually executing any instructions, then you can do
so with the -n or --dry-run flag

    $ java -jar magenta.jar -n TEST ContentApi::master 122

I'm a developer, how do I make my project deployable?
-------------------

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

Inside the deploy.json you specify what packages make up your application and
what types of machines they should be deployed to.  This is defined in the
packages dictionary:

    "packages": {
      "demo1": { 
        "type": "demo", 
        "apps": [ "demo-servers" ], 
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

  The type of package.  The various package types define what the deployable
  actions are for this package.  For example the resin-webapp type, we block
  the container, copy the files, restart the containear, test the ports, test a
  set of urls and unblock.

* Apps:

  The sort of machine to deploy to.  The systems machine interface will return
  a list of hosts with compatible apps, so this must match the responses from
  your operations teams deployment info scripts.

* Data:

  Overridable data for the package-type.  Each package type might have specific
  data, for example, at the guardian we might run multiple jetty-webapp
  instances on the same server, and so the packages can override the port that
  it expects the server to be listening on when it has finished restarting.

Each package should come with supporting files in the
`/packages/<package_name>` directory.  For jetty-webapps we simply copy the
entire directory into `/jetty-apps/<package_name>/` meaning that your war
should be in `/packages/<package_name>/webapps/<package_name>.war`

After the package definition, you need to define some deployment recipes.
These are essentially types of deployment that you might need to do.  If the
deployer does not specify a recipe to execute, the recipe called `default` will
be executed.

     "recipes": {
       "default": {
         "actions": [
           "demo1.deploy"
         ]
       }
     }

Each recipe contains a list of actions that will be executed in order.  The
actions are defined by the type that the package uses.

What is still left to do?
------

This system is used for deploying the Content API and Flexible Content systems
into production at the guardian, so in one sense you could say it has nothing
left to do, but in reality there are a number of features that are still needed
or strongly desired.

   * IRC Notifications of deployments
   * Use Jsch rather than calling bash/ssh as external shell processes
   * Add Django deployments
   * Add database version guards
   * Handle TeamCity pinning of artifacts
