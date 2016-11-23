<!--- prev:how-magenta-works next:artifacts.zip -->
Terminology
===========

**NOTE:** This relates to the legacy `deploy.json` configuration file and does not reflect the current state of 
Riff-Raff.

Before we dive into more detail, it is worth understanding a small number of terms
that are used by magenta to describe how a deploy should work.

### Package

  A *package* is an artifact (such as a WAR, JAR, other archive or a collection of
  static files and some associated metadata that defines the *deployment type* and the
  target infrastructure.

### Deployment Type

  *Deployment types* are defined in scala code inside the magenta library. It is this
  scala code that takes the *package* metadata and artifact and generates a list
  of tasks that should be run in order to deploy the *package*. A *deployment type*
  can have more than one *action* (for example there might be one action to deploy
  and another action to only restart each node).

  The deployment types that are defined in magenta are listed on the [types page](../types).

### Recipe

  *Recipes* define which *deployment types* and which *actions* on those *deployment
  types* are run. They are also able to declare dependencies on other *recipes*, which
  allows complex *recipes* to be built up from a number of simple *recipes*.
