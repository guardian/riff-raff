<!--- prev:how-magenta-works next:artifacts.zip -->
Terminology
===========

Before we dive into more detail, it is worth understanding a small number of terms
that are used by magenta to describe how a deploy should work.

### Package

  A *package* is an artifact (such as a WAR, JAR, other archive or a collection of
  static files and some associated metadata that defines the *package type* and the
  target infrastructure.

### Package Type

  *Package types* are defined in scala code inside the magenta library. It is this
  scala code that takes the *package* metadata and artifact and generates a list
  of tasks that should be run in order to deploy the *package*. A *package type*
  can have more than one *action* (for example there might be one action to deploy
  and another action to only restart each node).

  The package types that are defined in magenta (as or the time of writing) are:

  - **autoscaling** or **asg-elb** - use autoscaling to add instances with new version and then destroy old instances
  - **elasticsearch** - similar to autoscaling, but specialised for ElasticSearch instances
  - **jetty-webapp** and **resin-webapp** - deploy WAR file to a Jetty / Resin container
  - **django-webapp** - deploy a Python WSGI app running under Apache httpd
  - **executable-jar-webapp** - deploy an executable JAR file to server
  - **aws-s3** - copy files to an S3 bucket
  - **unzip-docroot** - copy files to an internal static file docroot (bypassing DDM)
  - **fastly** - use the Fastly API to upload a new configuration

### Recipe

  *Recipes* define which *package types* and which *actions* on those *package
  types* are run. They are also able to declare dependencies on other *recipes*, which
  allows complex *recipes* to be built up from a number of simple *recipes*.
