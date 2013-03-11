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
 * Use Assembly to create a self-executing jar
 * Add the dist plugin to your plugins.
   * Add this to project/project/Plugins.scala

			object MYAPPPlugins extends Build {
				lazy val plugins = Project("MY-APP-plugins", file("."))
				.dependsOn(
					uri("git://github.com/guardian/sbt-dist-plugin.git#1.6")
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
 * Ensure that the systems team have made your servers available as APP-ROLE in the deployinfo

What about deploying to the cloud?
----------------------------------

To deploy to the cloud, follow the above steps, but declare your package type as autoscaling.
You'll need to provide a `bucket` data to say where to put the artifact in S3.

The deploy will then do the following:

Take the package and upload it to the bucket you specified into the directory:
`<bucket>/<stage>` - it will use the <package> directory from the artifact.
So Content-Api-Concierge has a deploy.json like
```json
    "packages": {
        "content-api-concierge": {
            "type":"autoscaling",
            "data":{
                "port":"8080",
                "bucket":"content-api-dist"
            }
        }
    },
```
It will upload the file to s3://content-api-dist/<STAGE>/content-api-concierge

It will then look for an AutoscalingGroup that is tagged with the tag Role and the name of the package, and the tag Stage, and the stage you are deploying to.
In cloudformation you could use this:
```json
        "AutoscalingGroup":{
            "Type":"AWS::AutoScaling::AutoScalingGroup",
            "Properties":{
                "Tags":[
                    {
                        "Key":"Stage",
                        "Value":{ "Ref":"Stage" },
                        "PropagateAtLaunch":"true"
                    },
                    {
                        "Key":"Role",
                        "Value":"content-api-concierge",
                        "PropagateAtLaunch":"true"
                    }
                ]
            }
        },

```

In order to do all of this, You'll need an AWS key for deploying, which needs the AccessKey and SecretKey provided to RiffRaff via the configuration file, and you'll need to create the user in your IAM profile with permissions like:

```json
{
  "Statement": [
    {
      "Sid": "Stmt1362760208538",
      "Action": [
        "autoscaling:DescribeAutoScalingGroups",
        "autoscaling:DescribeAutoScalingInstances",
        "autoscaling:DescribeTags",
        "autoscaling:SetDesiredCapacity",
        "autoscaling:TerminateInstanceInAutoScalingGroup",
        "ec2:CreateTags",
        "ec2:DescribeInstances",
        "elb:DescribeInstanceHealth",
        "elasticloadbalancing:DescribeInstanceHealth",
        "elasticloadbalancing:DeregisterInstancesFromLoadBalancer"
      ],
      "Effect": "Allow",
      "Resource": [
        "*"
      ]
    },
    {
      "Sid": "Stmt1362760268494",
      "Action": [
        "s3:*"
      ],
      "Effect": "Allow",
      "Resource": [
        "arn:aws:s3:::*"
      ]
    }
  ]
}
```
