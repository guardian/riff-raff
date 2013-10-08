<!--- prev:artifacts.zip -->
Anatomy of deploy.json
======================

The `deploy.json` configuration file is used to describe the ways in which a
set of artifacts may be deployed onto the infrastructure. It has two sections:
_packages_ and _recipes_.

Conventions
-----------

From the beginning, magenta was designed to make heavy use of conventions in
order to infer defaults. As a result of this, it is possible to have a very
minimal deployment file.

    {
      packages: {
        riff-raff: {
          type: "executable-jar-webapp"
        }
      }
    }

A whole bunch of information is assumed by convention in this example:

 - No recipe exists - in this situation the _'default recipe'_ calls the `deploy` action on **all** packages
 - The app name is inferred to be the same as the package name (riff-raff)
 - The default port for the `executable-jar-webapp` type is 8080
 - the default healthcheck URL for the `executable-jar-webapp` is /management/healthcheck
 - the `executable-jar-webapp` will deploy the JAR file found at packages/riff-raff/riff-raff.jar

A longer example
----------------

A larger example is below, this time with two packages and three recipes.

    {
      packages:  {
        frontend-article:  {
          type: "executable-jar-webapp",
          apps: [ "frontend::article" ],
          data:  {
            healthcheck_paths: [ "management/healthcheck", "/" ]
          }
        },
        frontend-static:  {
          type: "aws-s3",
          apps: [ "aws-s3" ],
          data:  {
            bucket: "frontend-static",
            cacheControl: "public, max-age=315360000"
          }
        }
      },
      recipes:  {
        default:  {
          depends:  [
            "staticFilesUpload",
            "deploy"
          ]
        },
        deploy:  {
          actionsPerHost:  ["frontend-article.deploy"]
        },
        staticFilesUpload:  {
          actionsBeforeApp:  ["frontend-static.uploadStaticFiles"]
        }
      }
    }

This deploys an application which uses the `executable-jar-app` to deploy the actual application but also deploys
 static files to an S3 bucket.

### Looking at the recipes

A recipe has three valid keys:

 - `actionsPerHost` - takes an array of strings in the format `<packageName>.<actionName>` for actions that you wish
 to execute once for each host that magenta resolves
 - `actionsBeforeApp` - like `actionsPerHost` this also takes an array of strings in the `<packageName>.<actionName>`
  format - however this is only resolved once per app and the resulting tasks are run before any per host tasks
 - `depends` - takes an array of other recipe names which must be resolved before this recipe; this can also be used
 to run a series of other recipes as in this example where the _default_ recipe simply runs the _staticFilesUpload_
 recipe followed by the _deploy_ recipe