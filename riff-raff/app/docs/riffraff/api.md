<!--- next:hooksAndCD -->
API
===

Riff-Raff has a basic API for querying deploy history and starting deploys.  Depending on your use case you might also
want to look at the documentation for [external deploy requests](externalRequest).

All requests to the API require authorisation in the form of an API key.  These can easily be obtained through the
Riff-Raff web interface under the Configuration menu.  The key is always supplied as a GET or POST parameter called
`key`. Where timestamps are provided or accepted, these are formatted in ISO8601.

The API endpoints are all under /api.

API responses
-------------

Following the Guardian Content API conventions, all API endpoints will respond with a JSON document like the following:

    {
        "response": {
          "status": "ok"
        }
    }

History endpoint
----------------

`/api/history`

The history endpoint allows you to query the history of Riff-Raff deploys.  The results are paginated, much like
the history section in the web GUI.  By default the most recent deploys will be shown as below.

    {
        "response": {
            "status": "ok",
            "currentPage": 1,
            "pageSize": 20,
            "pages": 3,
            "total": 59,
            "filter": { },
            "results": [
                {
                    stage: "CODE",
                    projectName: "tools::dummy-app",
                    recipe: "default",
                    uuid: "19097f12-7a8d-4fc0-80bc-630f3cb43bdc",
                    build: "5",
                    deployer: "Simon Hildrew",
                    status: "Failed",
                    logURL: "http://gnm41175:9000/deployment/view/19097f12-7a8d-4fc0-80bc-630f3cb43bdc",
                    taskType: "Deploy",
                    time: "2013-09-03T12:37:56.738+01:00",
                    tags: {
                        branch: "master"
                    }
                }
                ...
            ]
        }
    }

The parameters that can be supplied are the same as those on the history web UI page:

  * `page` - The page of results to return
  * `pageSize` - Number of results provided per page
  * `status` - One of `Completed`, `Running`, `Failed` or `Not running` (waiting to run)
  * `stage` - One of the stages as show in the results pages
  * `task` - `Deploy` or `Preview`
  * `project` - Case insensitive search by project
  * `deployer` - The name of the deployer as it appears on the search results

Deploy view endpoint
--------------------

`api/deploy/view?uuid=<uuid>`

This can be used to view information for a specific deploy for which you know the UUID. The result format is the same
as provided in the history endpoint.

Deploy request endpoint
-----------------------

`api/deploy/request`

This endpoint can be used to start a deploy. It has parallels with the deploy screen in the web app. To start a deploy
you send a post with a json fragment containing your request.

An example fragment (sent as `application/json`) might be:

    {
      "project":"tools::dummy-app",
      "build":"17",
      "stage":"INFRA",
      "recipe":"default",
      "hosts":["10-252-94-200.gc2.dc1.gnm"]
    }

The response you receive will contain something like this:

    {
      "response": {
        "status":"ok",
        "request": {
          "project":"tools::dummy-app",
          "build":"17",
          "stage":"INFRA",
          "recipe":"default",
          "hosts":["10-252-94-200.gc2.dc1.gnm"]
        },
        "uuid":"42738f4a-40e2-4d82-b720-ddb4a22ad81c"
      }
    }

`recipe` and `hosts` are optional and default to `default` and the empty list respectively.

Deploy stop endpoint
--------------------

`api/deploy/stop`

This endpoint can be used to cancel a deploy (as much as is possible). This sets a stop flag which is periodically
checked by the deploy which will then abandon further tasks and sub-tasks.

To use this end point send a POST request with a `uuid` parameter which has the string representation of the deploy UUID
that you wish to stop.


Deploy Info endpoint
--------------------

`api/deployinfo`

The deployinfo endpoint provides a dump of the host and resource information that Riff-Raff has cached in memory. This
can be used to easily find servers running your application and also to chain Riff-Raff instances together such that
only one has to run the fairly costly API calls to retrieve the information.

The following parameters can be used to filter the host results:
  * `stage` - The stage in which the host exists
  * `app` - The app (substring search) that the host is used for
  * `hostList` - A comma separated list of hosts
