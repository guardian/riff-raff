# Contributing
First, and most importantly, thanks!

## Running locally
1. Install the requirements 
   - Java 11
   - SBT
   - Docker
You will also require `Deploy Tools - Developer` permissions from Janus in order to run locally. if you
do not run prism locally, you will need to be connected to the VPN when working remotely.

2. Create configuration
Create a configuration file at `~/.gu/riff-raff.conf`. At a minimum it looks like this,
replacing placeholders with appropriate values:

   ```
   artifact.aws.bucketName=<ARTIFACTS BUCKET NAME>
   build.aws.bucketName=<BUILDS BUCKET NAME>
   
   db.default.url="jdbc:postgresql://localhost:7432/riffraff"
   db.default.user="riffraff"
   db.default.hostname="riffraff"
   db.default.password="riffraff"
   
   lookup.prismUrl=<PRISM URL>
   lookup.source="prism"
   ```

3. From the root of the repository, run:

   ```sh
   ./script/start
   ```
   
   Add `--debug` to attach a remote debugger on port 9999.
4. Visit http://localhost:9000/
5. Details of how to configure Riff-Raff can then be found at http://localhost:9000/docs/riffraff/administration/properties

## Raising a PR
It's worth reading the [Pull Request recommendations](https://github.com/guardian/recommendations/blob/main/pull-requests.md) first.

Adding tests, particularly if you're modifying existing code, is preferable.

Tests can be run locally via:

```sh
./script/test
```

## A note about CI
Unfortunately, there is a long standing bug where CI will randomly fail with a message similar to:

```log
scala.reflect.internal.Symbols$CyclicReference: illegal cyclic reference involving class GetParameterResponse
```

We've not managed to fully resolve this. If you can, that'd be amazing!

The problem usually resolves itself if you re-run the build.
If you're still seeing the error, it's worth compiling and running the tests locally to be sure it's not a genuine issue with your branch.
