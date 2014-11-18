<!--- next:s3buckets prev:hooksAndCD -->
Making an external request for a deploy
=======================================

If you have an external system that would like to aid deployments of projects then you can create URLs that make
an interactive request to riff-raff to carry out a deploy.

For a given riff-raff installation, you will find the URL endpoint at /deployment/externalRequest.

Making a request to this endpoint will authenticate the user and then present a dialog with the details of the deploy.
The user can then click confirm to execute the deploy (or preview).

The parameters available are:

 - `project` - the project name from TeamCity
 - `build` - the build identifier in TeamCity
 - `stage` - the environment in which you would like the deploy carried out
 - `manualStage` - this is an alternative to the stage parameter and should be ignored for this form
 - `recipe` - choose the recipe to resolve
 - `action` - this can be `preview` or `deploy`

For example, to do a preview of a deploy of riff-raff in CODE you might write:
    `http://riff-raff/deployment/externalRequest?project=tools%3A%3Adeploy&build=333&stage=CODE&action=preview`

Note that you should generally URL encode your parameters.