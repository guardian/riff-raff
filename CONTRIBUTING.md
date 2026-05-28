# Contributing
First, and most importantly, thanks!

## Running locally 

### Credentials

You will require `Deploy Tools - Developer` or `Deploy Tools - Read-only` permissions from Janus in order to run locally.
If you do not run prism locally, you will need to be connected to the VPN when working remotely.

### Running locally in a dev container

Open the devcontainer file.  Restart in container.  You will now have all tooling installed.

1. Paste your credentials into the dev container terminal
1. Obtain the private configuration by running this script in the dev container:
   ```sh
   ./script/setup
   ```
1. You may need to edit the allowedGroups to match your user groups
1. Start the application by running this script in the dev container:
   ```sh
   ./script/start
   ```
   Add `--debug` to attach a remote debugger on port 9999.

### Running locally on the host laptop

1. Install the required software - see .tool-versions for details
1. Paste your credentials into the dev container terminal
1. Obtain the private configuration by running this script from the root of the repository:
   ```sh
   ./script/setup
   ```
1. You may need to edit the allowedGroups to match your user groups
1. Start the application by running this script from the root of the repository:
   ```sh
   ./script/start
   ```
   Add `--debug` to attach a remote debugger on port 9999.

### Interacting with the service

1. Visit http://localhost:9000/ on the host machine and login

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
