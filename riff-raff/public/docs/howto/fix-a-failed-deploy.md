# Fixing a failed deploy

## Background

Normally your Auto-Scaling Group will be configured with a maximum capacity that is _twice_ the 'desired'
capacity - this is to allow deploys to work by _doubling_ the desired capacity, bringing up new boxes,
before killing the old ones off.

When a Riff-Raff deploy fails, your Auto-Scaling Group will often be left in a 'bad' state, where
the desired capacity stays the _same_ as the specified maximum capacity for the ASG - once in this
state, Riff-Raff/Magenta will not be able to do any more automated deploys, because it can't double
the desired capacity in excess of your configured max.

## Step 1 - get the ASG back to it's normal size

Use the AWS console to change the desired size of the ASG back to the original value (typically half). Access the AWS console via Janus, navigate to the EC2 section, then click "Autoscaling Groups." The desired size can be modified by clicking the "edit" button in the "Details" tab. The Riff-Raff error and logs should let you know which ASGs have been used for your given deploy.
This will ensure that the new instances brought up by your failed deploy are terminated as Riff-Raff applies scale-in protection to existing instances before the deploy begins.

See [here](https://docs.aws.amazon.com/autoscaling/ec2/userguide/as-manual-scaling.html) for detailed steps on how to change the desired size of an ASG.

## Step 2 - remove scale-in protection
Part of the  deploy process, when successful, is to remove scale-in protection from healthy live instances so they will be identified as the redundant ones which are removed during the next deploy. If the deployment has failed, it is possible this scale-in protection has been added to instances without being removed, so it needs to be manually removed before attempting the next deploy.

In the same ASG section of AWS (see Step 1 above),  after completing Step 1, navigate to the "Instance management" tab to check and remove all instances for scale-in protection: Click the checkbox next to an instance ID, select the "Actions" drop-down", select "Remove scale-in protection" if available (it will only be available if the instance has scale-in protection), then confirm.

See [here](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-instance-protection.html) for more information on instance scale-in protection in ASGs.

## Step 3 - repeat for other ASGs
Check the Riff-Raff deployment logs to see if more than one ASG is used for the deploy. Repeat steps 1 and 2 for any other ASGs which may have been affected during the deploy. They may not be the ones listed as erroring, but could still have been halted at an intermediary state, and so need to be "reset" in order to do a successful redeployment.

## Step 4 -  do another deploy
Once the ASG is back to its normal size, redeploy the project to a known good version. 
This will ensure that the correct artifact is deployed and that all live boxes are in the correct state.