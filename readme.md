# Riff-Raff
"Deploy the transit beam"

## About
The Guardian's scala-based deployment system is designed to automate deploys by providing a web application that 
performs and records deploys, as well as providing various integration points for automating deployment pipelines.

## Documentation
Riff-Raff's documentation is available in the application (under the Documentation menu) but can also be viewed under
[riff-raff/public/docs](riff-raff/public/docs) in GitHub.

## Requirements
Riff-Raff has been built with the tools we use at the Guardian
and you will find it easiest if you use a similar set of tools. Riff-Raff:

 - relies on artifacts and `riff-raff.yaml` files describing builds being in S3 buckets with the artifacts having paths of 
  the form `project-name/build-number`
 - uses the AWS SDK and [Prism](https://github.com/guardian/prism) to do resource discovery
 - stores configuration, history and logs in a PostgreSQL database and a handful of DynamoDB tables

## In action
Screenshots don't do a lot to show how Riff-Raff works in practice - but here are
a handful anyway, just to give a hint.

***

![Deploy history](contrib/img/deployment_history.png)
The deploy history view - this shows all deploys that have ever been done (in this case filtered on PROD and projects containing 'mobile')

***

![Deploy log](contrib/img/deployment_view.png)
This is what a single deploy looks like - displaying the overall result and the list of tasks that were executed.

***

![Request a deploy](contrib/img/deployment_request.png)
The simple form for requesting a deploy can be seen here (further options are available after previewing)

***

![Continuous deployment configuration](contrib/img/deployment_continuous.png)
Riff-Raff polls for new builds frequently and can be configured to automatically start a deployment for newly completed builds

## Contributing
See [CONTRIBUTING.md](./CONTRIBUTING.md).