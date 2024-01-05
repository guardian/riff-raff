<!--- next:externalRequest prev:api -->
Continuous Integration and Deployment
=====================================

You want to continuously deploy your project into production. A typical pipeline (for feature branch style development)
 might be:

 - a developer merges a new feature to the default branch
 - the build server creates a new deployable build
 - Riff-Raff deploys the new build to a development environment
 - a set of automated integration tests run against the development environment
 - if the tests pass then Riff-Raff deploys the new build into production

In order to build this you need to be able to:

 - start a deploy automatically
 - trigger an action when a new deploy has completed successfully

Riff-Raff has a handful of features that makes it easy to build continuous integration and continuous deployment
pipelines.

Starting deploys automatically
------------------------------

A continuous deployment configuration is designed to watch for new builds and react to
 events. You can currently set up a configuration that reacts to a successful build. When the matching event occurs,
 Riff-Raff will start a deploy automatically to the specified environment.

In either case, the configuration can be set to filter by VCS branch (e.g. only builds of the default branch trigger
 a new deploy - allowing work on other branches to be safely ignored).

Deploys triggered by Continuous Deployment will have a deployer name of `Continuous Deployment`, but in any other
regard will look like a normal deployment.

You can temporarily disable a Continuous Deployment hook by changing the trigger to `disabled` in the configuration.

Triggering post-deploy actions
------------------------------

In order to trigger another action (or notification) when a deploy completes successfully, Riff-Raff provides *Post
 deploy hook* configurations. This is a simple mechanism that makes a HTTP GET request to a specified URL when a deploy
 completes that matches certain criteria.

You configure a hook by mapping a project name and stage to a URL.  It is also possible to temporarily disable hooks by
 modifying the enabled state.

It can be helpful to know more information about the build that has just completed. Riff-Raff can embed the following
values in your URLs substituting this syntax: `%deploy.<variable>%`

  * `build`
  * `project`
  * `stage`
  * `deployer`
  * `uuid`
  * `tag.XXXX` - where XXXX is the name of an available tag (for a list of tags it is easiest to look at the `tag` dict
   in the results of the _history_ and _deploy view_ API endpoints)
