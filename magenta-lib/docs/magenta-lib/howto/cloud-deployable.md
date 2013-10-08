<!--- prev:scala-deployable -->
How to deploy to the cloud
--------------------------

To deploy to the cloud, follow the above steps, but declare your package type as autoscaling.
You'll need to provide a `bucket` data to say where to put the artifact in S3.

The deploy will then do the following:

Take the package and upload it to the bucket you specified into the directory:
`<bucket>/<stage>` - it will use the <package> directory from the artifact.
So Content-Api-Concierge has a deploy.json like
```json
    "packages": {
        "content-api-concierge": {
            "type":"autoscaling",
            "data":{
                "port":"8080",
                "bucket":"content-api-dist"
            }
        }
    },
```
It will upload the file to s3://content-api-dist/<STAGE>/content-api-concierge

It will then look for an AutoscalingGroup that has two tags: one with the key 'Role' and the name of the package as the value and the other with the key 'Stage' and the name of the stage you're deploying to as the value.
In cloudformation you could use this:
```json
        "AutoscalingGroup":{
            "Type":"AWS::AutoScaling::AutoScalingGroup",
            "Properties":{
                "Tags":[
                    {
                        "Key":"Stage",
                        "Value":{ "Ref":"Stage" },
                        "PropagateAtLaunch":"true"
                    },
                    {
                        "Key":"Role",
                        "Value":"content-api-concierge",
                        "PropagateAtLaunch":"true"
                    }
                ]
            }
        },

```

In order to do all of this, You'll need an AWS key for deploying, which needs the AccessKey and SecretKey provided to RiffRaff via the configuration file, and you'll need to create the user in your IAM profile with permissions like:

```json
{
  "Statement": [
    {
      "Sid": "Stmt1362760208538",
      "Action": [
        "autoscaling:DescribeAutoScalingGroups",
        "autoscaling:DescribeAutoScalingInstances",
        "autoscaling:DescribeTags",
        "autoscaling:SetDesiredCapacity",
        "autoscaling:TerminateInstanceInAutoScalingGroup",
        "ec2:CreateTags",
        "ec2:DescribeInstances",
        "elb:DescribeInstanceHealth",
        "elasticloadbalancing:DescribeInstanceHealth",
        "elasticloadbalancing:DeregisterInstancesFromLoadBalancer"
      ],
      "Effect": "Allow",
      "Resource": [
        "*"
      ]
    },
    {
      "Sid": "Stmt1362760268494",
      "Action": [
        "s3:*"
      ],
      "Effect": "Allow",
      "Resource": [
        "arn:aws:s3:::*"
      ]
    }
  ]
}
```
