# Fixing a failed deploy

Normally your Auto-Scaling Group will be configured with a maximum capacity that is _twice_ the 'desired'
capacity - this is to allow deploys to work by _doubling_ the desired capacity, bringing up new boxes,
before killing the old ones off.

When a Riff-Raff/Magenta deploy fails, your Auto-Scaling Group will often be left in a 'bad' state, where
the desired capacity stays the _same_ as the specified maximum capacity for the ASG - once in this
state, Riff-Raff/Magenta will not be able to do any more automated deploys, because it can't double
the desired capacity in excess of your configured max.

Once the ASG is back to it's normal size, do another automated deploy to verify that you can still
deploy successfully, and that all live boxes are in the correct state.

## Reducing the size of an ASG

When Riff-Raff starts an autoscaling deploy it will protect existing instances against scale in events. 
This is done to make it easy to recover from this kind of failure. If an autoscaling deploy fails it is 
usually because the new instances don't pass their healthcheck and come into service.

To rollback the deploy you should:
 - change the desired size of the ASG back to the original value (typically half)
 - redeploy the project to a known good version (this will ensure that the correct artifact is deployed
 and that the scale in protection is cleared)

## Manually Kill Bad Boxes

_**Note: you probably don't need to do this anymore - reducing the size of an ASG should do the right thing 
as scale in protection is applied to existing instances.**_

Use the AWS CLI [`terminate-instance-in-auto-scaling-group`](http://docs.aws.amazon.com/cli/latest/reference/autoscaling/terminate-instance-in-auto-scaling-group.html)
command to kill bad boxes by instance-id (eg. `i-badbadba`), making sure you leave
good (old) boxes still operational:

```
$ aws autoscaling terminate-instance-in-auto-scaling-group --should-decrement-desired-capacity --instance-id i-badbadba
```

Note that you must include `--should-decrement-desired-capacity`, otherwise the ASG will just bring up a new box.
