The Riff-Raff API
=================

Riff-Raff has a basic API for querying deploy history and starting deploys.  Depending on your use case you might also
want to look at the documentation for [external deploy requests](externalRequest).

All requests to the API require authorisation in the form of an API key.  These can easily be obtained through the
Riff-Raff web interface under the Configuration menu.  The key is always supplied as a GET or POST parameter called
`key`.

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
                    time: 1358444866420
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