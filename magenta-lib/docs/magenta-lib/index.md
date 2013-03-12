Magenta library documentation
=============================

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

  The type of package.  The various package types define what the deployable
  actions are for this package.  For example the jetty-webapp type, we block
  the container, copy the files, restart the container, test the ports, test a
  set of urls and unblock.

* Data:

  Overridable data for the package-type.  Each package type might have specific
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


I'm a developer at the guardian, how do I make my scala project deployable
--------------------------------------------------------------------------

You are using Scala, SBT and so on, so you can apply the following rules.

 * Ensure you are using SBT 0.11.2 (or later)
 * Add the dist plugin to your plugins.
   * Add this to project/project/Plugins.scala

			object MYAPPPlugins extends Build {
				lazy val plugins = Project("MY-APP-plugins", file("."))
				.dependsOn(
					uri("git://github.com/guardian/sbt-dist-plugin.git#1.7")
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
			            "type":"jetty-webapp",
			        }
			    }
			}

 * Run `./sbt dist` locally and check that the artifact.zip is created correctly
 * Update the teamcity build to use the dist target
 * Ensure that the systems team have made your servers available as APP-ROLE in the deployinfo
