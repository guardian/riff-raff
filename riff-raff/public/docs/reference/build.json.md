The build.json file
===================

The type ahead of the project name and build number and also the continuous deployment feature is powered by the
`build.json` files that are created for each project.

This file is normally created by [actions-riff-raff](https://github.com/guardian/actions-riff-raff).

```json
{  
  "projectName": String,
  "buildNumber": String(Long),
  "startTime": String(DateTime),
  "vcsURL": String,
  "branch": String,
  "revision": String
}
```

### projectName

The name of the project. This is ued to identify and select the project in Riff-Raff.

_Required:_ Yes.

### buildNumber

The build number of the project. This is a string but is parsed into a `Long` inside Riff-Raff. This should be a unique
and incrementing identifier for a particular CI build. You should use the build ID from TeamCity, CircleCI or Travis for
this.

_Required:_ Yes.

### startTime

An ISO-8601 format string representing the date time that this build started. This is displayed in the type ahead to 
make it easier to identify a build.

_Required:_ Yes.

### vcsUrl

The URL to the VCS. This would typically be a GitHub URL such as `https://github.com/guardian/riff-raff`. This is used,
along with the revision, to generate links to the code that generated this build.

_Required:_ Yes.

### branch

The name of the VCS branch. This is displayed in the type ahead and also in the audit trail. Supplying this makes it 
easy to identify in Riff-Raff whether a build is from the default branch or not. It can also be used as a
condition for triggering continuous deployments.

_Required:_ Yes.

### revision

The VCS revision ID that this build was created from. This will typically be a git commit hash. This is used, along with
the `vcsUrl` to link to the code for this build and could also be used to generate diffs between deployments. 

_Required:_ Yes.
