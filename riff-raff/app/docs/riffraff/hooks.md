Post Deployment Hooks
=====================

There are settings in which you want to be notified when a deployment completes successfully.  You might have a deploy
 as part of a continuous deployment pipeline that triggers a testing suite after every deploy to the testing
 environment.

You configure a hook by mapping a project name and stage to a URL.  It is also possible to temporarily disable hooks by
 modifying the enabled state.

Post deploy hooks allow you to instruct riff-raff to make a GET request to a URL after a deploy has completed.

You can expand the following variables in your URLs by using this syntax: `%deploy.<variable>%`

  * `build`
  * `project`
  * `stage`
  * `recipe`
  * `hosts` (comma separated list of hosts)
  * `deployer`
  * `uuid`