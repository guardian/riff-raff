S3 Artifact Layout
==================

Riff-Raff looks in S3 buckets for artifacts and metadata about the artifacts. In fact it uses three buckets, one
actually holds the artifacts' configuration and assets whilst the other two hold different types of metadata.

In all three cases Riff-Raff uses a key prefix of `<projectName>/<buildNumber>` to find the files associated with a
particular artifact. e.g. for build `27` of the `tools::prism` Riff-Raff will use a key prefix of `tools::prism/27`.

Artifact bucket
---------------

The artifact bucket holds the configuration files (`riff-raff.yaml`) and related assets. Going back to our `tools::prism`
example, Riff-Raff would expect to find the riff-raff.yaml file for the build at `tools::prism/27/riff-raff.yaml`.
Assets for the build would be further nested under content directories that match the names of the deployments in the
`riff-raff.yaml` file.

For example, given a tools::prism configuration file (below), during the `uploadArtifacts` action of the `autoscaling`
deployment type Riff-Raff would use the contents of the `tools::prism/27/prism` prefix. This can be overridden using the
`contentDirectory` field of a deployment.
```yaml
regions: [eu-west-1]
stacks: [deploy]
deployments:
  prism:
    type: autoscaling
    parameters:
      bucket: deploy-tools-dist
```

Thus the layout of the bucket is:
```
artifact-bucket
|- tools::prism
|  |- 27
|  |  |- riff-raff.yaml
|  |  `- prism
|  |     `- prism.tar.gz
|  `- 28
|     |- riff-raff.yaml
|     `- prism
|        `- prism.tar.gz
`- anotherProject
   `- buildId
```

Build bucket
------------

The build bucket stores [`build.json`](build.json.md) files. These contain the metadata about an artifact such as the 
VCS url and VCS revision ID. The files are used to provide the type ahead and is also monitored for changes which drives 
the continuous deployment component of Riff-Raff.

**IMPORTANT:** When uploading assets into the buckets ensure that the build.json is uploaded last to avoid any race 
conditions. 

Tags bucket
-----------

The tags bucket contains metadata about what tags exist on different artifacts. This is consulted by Riff-Raff and tags
are displayed during deployment. For example this can be used to indicate that a build has passed or failed post-CI 
integration tests.