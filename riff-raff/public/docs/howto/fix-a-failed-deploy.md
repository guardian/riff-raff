# Fixing a failed deploy

## Background

Normally your Auto-Scaling Group will be configured with a maximum capacity that is _twice_ the 'desired'
capacity - this is to allow deploys to work by _doubling_ the desired capacity, bringing up new boxes,
before killing the old ones off.

When a Riff-Raff deploy fails, your Auto-Scaling Group will often be left in a 'bad' state, where
the desired capacity stays the _same_ as the specified maximum capacity for the ASG - once in this
state, Riff-Raff/Magenta will not be able to do any more automated deploys, because it can't double
the desired capacity in excess of your configured max.

## Step 1 - get the ASG back to its normal size

Be aware that reducing the number of instances can lead to outages if they are under stress. Always check with the relevant team before peforming this action.

Use the AWS console to change the desired size of the ASG back to the original value (typically half). 
This will ensure that the new instances brought up by your failed deploy are terminated as Riff-Raff applies scale-in protection to existing instances before the deploy begins.

See [here](https://docs.aws.amazon.com/autoscaling/ec2/userguide/as-manual-scaling.html) for detailed steps on how to change the desired size of an ASG.

## Step 2 -  do another deploy
Once the ASG is back to it's normal size, redeploy the project to a known good version. 
This will ensure that the correct artifact is deployed and that all live boxes are in the correct state.





