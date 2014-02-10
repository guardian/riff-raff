<!-- prev:dashboards -->
AWS SNS Topic
=============

Riff-Raff can be configured to publish notifications of deploy start, complete and fail events.

Users can then consume events by subscribing to the topic by any of the subscription mechanisms supported by the AWS
SNS service.

Notification messages are in JSON format and has a number of fields. An example message is:

    {
      "id":"df2107d7-252d-4b06-bd87-8a1da00e4025",
      "project":"tools::dummy-app",
      "build":"22",
      "stage":"PROD",
      "recipe":"default",
      "deployer":"Simon Hildrew",
      "hostList":[],
      "text":"Deploy of tools::dummy-app started",
      "event":"DeployStarted",
      "adjective":"started",
      "href":"http://localhost:9000/deployment/view/df2107d7-252d-4b06-bd87-8a1da00e4025",
      "createTime":"2014-02-10T18:38:59.870Z"
    }