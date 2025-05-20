# Fixing a failed deploy

> [!NOTE]
> This guide is only necessary when using the legacy `autoscaling` deployment type.
> When using the new [RollingUpdate deployment mechanism](https://docs.google.com/document/d/18M6nW6bknvFYjICMaSmDwkIisAuHsn4y_HSTdKu7Z-o/edit?tab=t.0), a failed deployment will automatically roll back.

## Background

Normally your Auto-Scaling Group will be configured with a maximum capacity that is _twice_ (or sometimes _four times_) the 'desired'
capacity - this is to allow deploys to work by _doubling_ the desired capacity, bringing up new boxes,
before killing the old ones off.

When a Riff-Raff deploy fails, your Auto-Scaling Group could be left in a 'bad' state. 
This can manifest itself in one of several ways. 

1. The desired capacity stays the same as the maximum capacity.
2. The instance count (number of instances) is less than the maximum capacity, but has not fallen down to the desired capacity. 
3. The instance count is equal to the desired capacity but some instances have "scale in" protection.

In any of those cases, Riff-Raff might not be able to properly complete further deploys.

## Step 1 - get the ASG back to its normal size

Be aware that reducing the number of instances can lead to outages if they are under stress. Always check with the relevant team before performing this action.

Access the AWS console via Janus, navigate to the EC2 section, then click "Autoscaling Groups."

Use the AWS console to change the desired size of the ASG back to the original value. The desired capacity can be equal to or more than the minimum capacity, but should be less than half of the maximum capacity. 

The desired size can be modified by clicking the "edit" button in the "Details" tab. The Riff-Raff error and logs should let you know which ASGs have been used for your given deploy. This will ensure that the new instances brought up by your failed deploy are terminated as Riff-Raff applies scale-in protection to existing instances before the deploy begins.

The operation of adjusting the number of instances to match the desired capacity takes a few seconds while AWS terminates instances. If this step doesn't complete correctly, meaning that you are not seeing the number of instance fall down to the desired capacity that you specified, then you might need to perform Step 2 to unlock instances with "scale in" protection. 

See [here](https://docs.aws.amazon.com/autoscaling/ec2/userguide/as-manual-scaling.html) for detailed steps on how to change the desired size of an ASG.

## Step 2 - remove scale-in protection

If the deployment has failed, it is possible that some instances will still have scale-in protection enabled. In some cases this needs to be manually removed before attempting the next deploy.

In the same ASG section of AWS (see Step 1 above), after completing Step 1, navigate to the "Instance management" tab to check and remove all instances for scale-in protection: Click the checkbox next to an instance ID, select the "Actions" drop-down, select "Remove scale-in protection" if available (it will only be available if the instance has scale-in protection), then confirm.

See [here](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-instance-protection.html) for more information on instance scale-in protection in ASGs, or in the case the UI navigation has shifted from what is described above. If the UI has changed, please open a PR to update these documents [here](https://github.com/guardian/riff-raff).

## Step 3 - repeat for other ASGs

Check the Riff-Raff deployment logs to see if more than one ASG is used for the deploy. Repeat steps 1 and 2 for any other ASGs which may have been affected during the deploy. They may not be the ones listed as erroring, but could still have been halted at an intermediary state, and so need to be "reset" in order to do a successful redeployment.

## Step 4 - do another deploy

Once the ASG is back to its normal size, redeploy the project to a known good version. 
This will ensure that the correct artifact is deployed and that all live boxes are in the correct state.