#### 10th February 2014

Add the ability to publish messages on an AWS SNS topic.

#### 21st January 2014

Update UI to Twitter Bootstrap 3. Tweak a few bits of the API.

#### 6th January 2014

Add a new deployment type for updating AWS cloudformation templates.

#### 1st November 2013

Introduce new abstraction layer for looking up infrastructure information. This is implemented for both the internal
 "DeployInfo" mechanism and also for the new Prism service with selection of which to use being placed behind a switch.

#### 24th October 2013

Massively overhaul documentation - this included changing how the package types and parameters are implemented
under the hood so that they can be documented dynamically.

**Note: the _asg-elb_ alias for the _autoscaling_ deployment type has been removed**

#### 2nd October 2013

Change the deploy hooks so that multiple hooks can be added for a project and stage. Also added extra substitutions
so that tags on a deploy can now be substituted into the URL

#### 20th September 2013

Add a timeout to the deployinfo poller. It will now try to kill off a process that has not exited after a timeout
period.

#### 19th September 2013

Fixed another preview failure case, such that failures are more usefully presented to the user.

Highlight to the end user when deploys are stale and added a boolean field to the deployinfo API.

#### 16th September 2013

Change how previews are implemented so that failures are not silently dropped.

#### 3rd September 2013

Metrics for number of concurrent tasks and task start latency have been added to make it easier to identify performance
 issues.

API date-times are now returned in the ISO8601 format

#### 2nd September 2013

Continuous deployment has been overhauled. Deploys can now be triggered either by a new build successfully completing
or by an existing successful build being tagged. Note that in order for a newly tagged build to be picked up, the build
itself must not be older than 10 days (this value is configurable).

The deployment information is now dated so it is easy to see if the server information is no longer updating.

#### 21st August 2013

Add variable substitutions to hook URLs such that build numbers and projects can be encoded into the URL that is called.

#### 20th August 2013

Add API endpoint for stopping deploys.

#### 19th August 2013

Add API endpoint for starting deploys.

#### 12th August 2013

Change credential handling to be generic rather than based solely around S3/AWS.

#### 9th August 2013

Increase the akka task executor factor to create enough runners to match the configured number of concurrent deploys.

#### 16th July 2013

Enhance the CopyFile task and Webapp types in order to allow multiple roots to be copied independently and allow
mirroring to be done (thus not leaving old files on the target machines).

Two new properties are now on the webapp types. `copyRoots` is an array of directories to copy from the package
directory in the artifact.zip. `copyMode` can be set to `additive` (default) or `mirror`.  In `mirror` mode, the
files are mirrored to the destination and any files present on the target but not in the source directory will be
removed.

#### 8th July 2013

Record the details of the node that actually executes the deploy in the database so we can keep a history (primarily
for migration).

Introduce a grace period when doing an autoscaling deploy in order to work around a but in the AWS ELB
service. See https://forums.aws.amazon.com/thread.jspa?messageID=457489 for info on the problem. We work
around it by pausing briefly before asking for the status of the ELB backends which seems to ensure
we have a reliable answer from the API.

#### 1st July 2013

Modify the way the config is set up to ensure that the TeamCity location is not hardcoded.

#### 3rd June 2013

Magenta is now smarter in the way it orders hosts. Previously it didn't modify the order of hosts that was
passed in through deployinfo. However, it is better if we alternate between datacentres. Riff-Raff will now
use the `group` provided by deployinfo and ensure that the order of hosts alternate between different groups.

#### 29th May 2013

Replaced the Java unzip process with a call out to the local Linux command.  This was primarily done
 so that file permissions are preserved, but it is also a damn sight faster as there is no longer a need to
 buffer the entire file in RAM.

Also worked around a bug caused by the MarkDown rendering library not being thread safe.

#### 22nd May 2013

Reworked the preview functionality in order to prevent dog-piling.  Prior to this work, if a preview took longer
than the ZXTM timeout, multiple previews would be fired off in the background - often causing Riff-Raff to
stop responding for a short period of time.

#### 13th May 2013

Added more fine grained control to the S3Upload task, mainly for the Pasteup project.

You can now specify boolean properties `prefixStage` and `prefixPackage` (both true by default) which
can be used to disable the stage and/or package names being prefixed to the key of each file
being uploaded into an S3 bucket.

The property `bucketResource` can be specified instead of `bucket` in order to lookup the name of the bucket
from the deployinfo resource data instead of using a hard coded value.

Finally, the `cacheControl` property can be an array of dicts containing `pattern` and `value` keys.  Each
file to be uploaded is matched against regular expressions provided in the `pattern` key (in the order of the
array). The `value` key of the first match is used for the cache control for that key in the bucket. If no match
is found, then no cache control is explicitly set.

#### 10th May 2013

Migrate from play 2 to 2.1 (and also scala 2.9 to 2.10).

#### 7th May 2013

Add graphs to the history page showing the number of deploys that have been done recently.  The graph will return
up to 30 days with no filter and up to 90 days with a filter applied.

#### 11th Apr 2013

The AWS autoscale deploy now suspends AlarmNotifications during a deploy.

#### 3rd Apr 2013

Add deployinfo endpoint at /api/deployinfo to return the in-memory cache of the most recently returned
`deployinfo.json` file.  This is updated roughly once a minute.

You can now make a riff-raff instance obtain deployinfo from the API endpoint of another instance.

#### 2nd Apr 2013

jsonp responses to api endpoints have now been added.  Adding format=jsonp&callback=jsCallback will do what you
expect.

The default recipe for a particular stage and project combination can now be set in the resource listing, making
certain types of migration much easier.

#### 7th Mar 2013

Improvements to the django app deploy package and tasks.

#### 7th Feb 2013

VCS information is now collected when possible from TeamCity.  This is exposed via the API and also via a tooltip
in the history pane.

The tooltip on the dashboard and in the deploy screen has been given a little polish and now displays the branch as
well.

Dashboards are simpler to use for a set of related projects, you can now specify project terms to search by as well
as exact matches.

Disabled hooks and continuous deploy configurations are now highlighted to make it obvious when they are disabled.

#### 6th Feb 2013

Pinned builds in TeamCity are now tidied up so there are no more than 5 pinned builds for any build configuration.

#### 4th Feb 2013

Added housekeeping task that summarises deploys after 90 days.  The detailed log information is deleted at this point.

#### 1st Feb 2013

Fixed a bug that missed builds for continuous deployment.  Also made incremental TeamCity build retrieval possible,
meaning that updating the current build list is much less strain on TeamCity and so can be done more frequently.

#### 30th Jan 2013

Fixed a bug which would throttle concurrent deploys to only two simultaneously running tasks across all deploys.

Riff-Raff now stores the VCS branch name if available from TeamCity at the time of the deploy.  This is shown on the
history page and the history API.

#### 29th Jan 2013

Continuous Deployment configuration is now done through a CRUD interface in the webapp rather than being configured
in the properties file.  This update also added the ability to filter by a regex on the branch name (e.g. can filter
so that only builds from the master branch are deployed)

#### 25th Jan 2013

Overhauled the deploy screen to display the most recent successful deployments of a project, once a project has been
selected.

#### 23rd Jan 2013

Add [dashboard](docs/riffraff/dashboards) functionality for quickly seeing the last build deployed in different environments.

#### 22nd Jan 2013

Riff-Raff can now pin builds in TeamCity automatically once the build has completed.  New properties under `teamcity`
are used to configure this.

#### 18th Jan 2013

Added API endpoints to Riff-Raff, initially for searching the history but the groundwork has now been laid to
add more endpoints easily.

Documentation available at [docs/riffraff/api](docs/riffraff/api)

#### 17th Jan 2013

Deploys can now be interrupted.  Obviously this is useful when a long running deploy is accidentally started, or
a deploy is causing unintended side effects.

Please be aware that doing this may well leave the target systems in an inconsistent state.

Once the button to stop the deploy has been clicked the deploy will stop at the next opportune moment.  This will either
happen after the current task has finished or, in the case of tasks that poll in a loop, after the next loop has
finished.

If you are not viewing the log on the same server as it was started on then you will instead see a button that will
 take you to the log on the running server.

#### 9th Jan 2013

The preview system has been completely overhauled, bringing the magenta concepts of recipes to the surface and making
it easier to understand how the resolver has come up with the task list.

The resolver has also been changed to ensure that recipes which have per-host tasks are omitted if no hosts are found.

#### 3rd Jan 2013

Resolution of tasks has been changed so that recipes included by multiple recipes are not run multiple times.

#### 2nd Jan 2013

Automatic log scrolling has finally been added.  Your browser will now scroll to the bottom of the log when new data is
added.  Scrolling far enough up from the bottom will disable this behaviour, scrolling back down will enable it again.

#### 31st Dec 2012

Various changes to make it easier to develop on a local machine.  By default there is no external dependency on TeamCity
or the deployinfo executable.  Deployinfo can now be configured to fetch from any URL or executable.

#### 19th Dec 2012

Added pagination to history pages

#### 18th Dec 2012

Added filtering and searching to history pages

#### 6th Dec 2012

Completely re-wrote database mapping layer to decouple the internal data model from that serialised to the database.

This will make it much easier to develop with going forward and also makes searching the history table significantly
faster as less data is transferred from mongo.