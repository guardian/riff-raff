Advanced settings
=================

There are some advanced settings that won't normally be required but can be used in specific circumstances. 

## Update Strategy

The update strategy parameter controls the risk appetite of a given deploy. The aim is to reduce incidents resulting from unintentional changes such as deleting a stateful resource like a DynamoDB table or a resource like a load balancer that has a DNS entry pointed to it.

**Note: At the time of writing this is only observed for some deploys under some circumstances.**

Specifically, this currently means that when your deploy applies updates to a cloudformation stack, Riff-Raff will apply a stack update policy before executing the update. When you are using the default _MostlyHarmless_ update strategy this policy will prohibit the deletion and replacement of certain resource types. When you are using the _Dangerous_ update strategy this policy will allow any change to take place.

In practice, we expect most deploys to be run using the _MostlyHarmless_ strategy. In the case that this fails with a stack policy error you can review the deployment and then use the _Dangerous_ strategy. It is recommended that you manually check (by looking at the change set) that you really do want the resources in question to be deleted before using the _Dangerous_ strategy to execute destructive actions.
