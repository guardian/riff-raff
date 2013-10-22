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
      packages: {
        frontend-article: {
          type: "executable-jar-webapp",
          apps: [ "frontend::article" ],
          data: {
            healthcheck_paths: [ "management/healthcheck", "/" ]
          }
        },
        frontend-static: {
          type: "aws-s3",
          apps: [ "aws-s3" ],
          data: {
            bucket: "frontend-static",
            cacheControl: "public, max-age=315360000"
          }
        }
      },
      recipes: {
        default: {
          depends: [
            "staticFilesUpload",
            "deploy"
          ]
        },
        deploy: {
          actionsPerHost: ["frontend-article.deploy"]
        },
        staticFilesUpload: {
          actionsBeforeApp: ["frontend-static.uploadStaticFiles"]
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

In the example above - if the `default` recipe is executed then it will simply execute the two recipes that it depends
on. This will result in the tasks for `frontend-static.uploadStaticFiles` followed by `frontend-article.deploy` being
executed. A recipe will never be included more than once, even if multiple recipes that are being executed depend on it.
Each of those recipes can be executed separately, in this case allowing just the static files to be uploaded or just the
deploy to be carried out.

### Looking at the packages

A package has four valid keys:

 - `type` - this is a mandatory field that specifies which deployment type in magenta to instantiate (choose from
 the [types that are implemented](../types))
 - `apps` - takes an array of strings of apps (typically only one, default: package name) - this is used to
 lookup infrastructure resources such as instances, credentials or autoscaling groups depending on the deployment type
 - `data` - a dict (default: empty) that contains parameters for the deployment type
 - `fileName` - takes a string (default: package name) that determines where the associated files are in the
 artifacts.zip file - for example, in the example above for the package `frontend-article` the default location
 for associated files would be `/packages/frontend-article/`; this overrides the subdirectory name so that multiple
 packages can use the same set of files

An example of when you might use `fileName` is if you were to add a second package that deployed the application using
a different deployment type. You might do this if you were transitioning from one deployment mechanism to another. Such
a package might look like this:

    frontend-article-autoscaling: {
      type: "autoscaling",
      apps: [ "frontend::article" ],
      fileName: "frontend-article",
      data: {
        healthcheck_paths: [ "management/healthcheck", "/" ]
      }
    }

