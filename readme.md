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


I'm a developer at the guardian, how do I make my scala project deployable
--------------------------------------------------------------------------

You are using Scala, SBT and so on, so you can apply the following rules.

 * Ensure you are using SBT 0.11.2 (or later)
 * Add the dist plugin to your plugins.
   * Add this to project/project/Plugins.scala

			object MYAPPPlugins extends Build {
				lazy val plugins = Project("MY-APP-plugins", file("."))
				.dependsOn(
					uri("git://github.com/guardian/sbt-dist-plugin.git#1.6")
				)
			}

   * Add this to build.sbt

			Seq(com.gu.SbtDistPlugin.distSettings :_*)
			distPath := file("/r2/ArtifactRepository/MY-APP/trunk") / ("trunk-build." + System.getProperty("build.number", "DEV")) / "artifacts.zip"
			distFiles <+= (packageWar in Compile) map { _ -> "packages/MY-APP/webapps/MY-APP.war" }
			distFiles <++= (sourceDirectory in Compile) map { src => (src / "deploy" ***) x (relativeTo(src / "deploy"), false) }

   * Add a deploy.json under `src/main/deploy`, something like:

			{
			    "packages":{
			        "MY-APP":{
			            "type":"resin-webapp",
			            "apps":[ "APP-ROLE" ]
			        }
			    },
			    "recipes": {
			        "default": {
			            "actions": [
			                "MY-APP.deploy"
			            ]
			        }
			    }
			}

 * Run `./sbt dist` locally and check that the artifact.zip is created correctly
 * Update the teamcity build to use the dist target
 * Ensure that the systems team have made your servers available as APP-ROLE in the deployinfo


What is still left to do?
------

See the `TODO.txt` file in this project