# Fixing a failed deploy

Normally your Auto-Scaling Group will be  configured with a maximum capacity that is _twice_ the 'desired'
capacity - this is to allow deploys to work by _doubling_ the desired capacity, bringing up new boxes,
before killing the old ones off.

When a Riff-Raff/Magenta deploy fails, your Auto-Scaling Group will often be left in a 'bad' state, where
the desired capacity stays the _same_ as the specified maximum capacity for the ASG - once in this
state, Riff-Raff/Magenta will not be able to do any more automated deploys, because it can't double
the desired capacity in excess of your configured max.

You need to manually fix this problem, and this document gives some possible approaches for doing it.

Once the ASG is back to it's normal size, do another automated deploy to verify that you can still
deploy successfully, and that all live boxes are in the correct state.


## A) Manually Kill Bad Boxes

Use the AWS CLI [`terminate-instance-in-auto-scaling-group`](http://docs.aws.amazon.com/cli/latest/reference/autoscaling/terminate-instance-in-auto-scaling-group.html)
command to kill bad boxes by instance-id (eg. `i-badbadba`), making sure you leave
good (old) boxes still operational:

```
$ aws autoscaling terminate-instance-in-auto-scaling-group --should-decrement-desired-capacity --instance-id i-badbadba
```

Note that you must include `--should-decrement-desired-capacity`, otherwise the ASG will just bring up a new box.

## B) Trust the ASG to Kill the Right Boxes

If your Auto-Scaling Group has a termination policy of [`NewestInstance`](http://docs.aws.amazon.com/AutoScaling/latest/DeveloperGuide/AutoScalingBehavior.InstanceTermination.html#custom-termination-policy
),
you should theoretically be able to just set ASG desired capacity back to it's 'normal' value, and it will kill
the newest boxes, which is _probably_ what you want.

```
$ aws autoscaling set-desired-capacity --auto-scaling-group-name Blarg-ASG --desired-capacity 3
```
