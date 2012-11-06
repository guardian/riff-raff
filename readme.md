Magenta
=======

"Deploy the transit beam"

About
-----

The Guardian's scala-based deployment library is designed to help automate
deploys by localising knowledge about how to orchestrate deploys within a
single system, but having defined interfaces with the applications and the
system on to which the deploys will happen.

Documentation
-----

Most of the documentation has now been moved under [magenta-lib/docs](magenta-lib/docs) (for the main deployment library)
and [riff-raff/app/docs](riff-raff/app/docs) (for the web frontend).

I like the command line, How do I deploy something?
---------------------

Use the CLI tool found in the magenta-cli module.

If you are executing a task that requires upload to Amazon S3 then first do...

    $ export aws_access_key=[YOUR_ACCESS_KEY]
    $ export aws_secret_access_key=[YOUR_SECRET_KEY]

Magenta is responsible for locating the deployable artifacts, calculating the
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


What is still left to do?
------

See the `TODO.txt` file in this project