# Contributing
First, and most importantly, thanks!

## Running locally
1. Install the requirements 
   - Java 11
   - SBT
   - Docker
You will also require `Deploy Tools - Developer` permissions from Janus in order to run locally. If you
do not run prism locally, you will need to be connected to the VPN when working remotely.

1. Create configuration
Create a configuration file at `~/.gu/riff-raff.conf`. There is an example configuration file (`DEV.conf`) in the private configuration
bucket in the Deploy Tools account. Ask a DevX colleague if you're not sure how to find this.

1. From the root of the repository, run:

   ```sh
   ./script/start
   ```
   
   Add `--debug` to attach a remote debugger on port 9999.
1. Visit http://localhost:9000/
1. Details of how to configure Riff-Raff can then be found at http://localhost:9000/docs/riffraff/administration/properties

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
