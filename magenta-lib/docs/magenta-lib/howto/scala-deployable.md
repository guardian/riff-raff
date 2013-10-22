<!--- prev:make-deployable next:cloud-deployable -->
How do I make my scala project deployable?
==========================================

You are using Scala, SBT and so on, so you can apply the following rules.

 * Ensure you are using SBT 0.11.2 (or later)
 * Use Assembly to create a self-executing jar
 * Add the dist plugin to your plugins.
   * Add this to project/project/Plugins.scala

			object MYAPPPlugins extends Build {
				lazy val plugins = Project("MY-APP-plugins", file("."))
				.dependsOn(
					uri("git://github.com/guardian/sbt-dist-plugin.git#1.7")
				)
			}

   * In your SBT build you'll want to do the following:
     * Add the default dist settings to your build

        .settings(distSettings: _*)

     * Declare the packages to put into the artifact.zip
       .settings(
         distFiles <++= (sourceDirectory in Compile) map { src => (src / "deploy" ***) x rebase(src / "deploy", "") },
         distFiles <+= (assembly in Compile) map { _ -> "packages/<package>/<jarfile>" }
       )


   * Add a deploy.json under `src/main/deploy`, something like:

			{
			    "packages":{
			        "<package>":{
			            "type":"executable-jar-webapp",
			        }
			    }
			}

 * Run `./sbt dist` locally and check that the artifact.zip is created correctly
 * Update the teamcity build to use the dist target
