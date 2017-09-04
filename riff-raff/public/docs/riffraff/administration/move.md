<!--- prev:auth -->
Moving Riff Raff to a New Host
==============================

Riff Raff is only deployed to one host at a time, and sometimes it will need moving.

 1. Add `AddToLoadBalancer` to the Auto Scaling Group's Suspended Processes.
 2. Increase max/desired capacity to 2. 
 3. Block queueing new deploys on the new box.
 4. Block queueing new deploys on the old box.
 5. Add new box to the load balancer.
 6. Remove old box from ASG and terminate.
 7. Unblock deploys.
 8. Resume add to load balancer. 
 
 
 Blocking Queueing Deploys
 -------------------------
 To check switch status:
  `curl http://localhost:18080/management/switchboard`
 
 To block queueing of new deployments:
 `curl -d "" http://localhost:18080/management/switchboard?enable-deploy-queuing=OFF`
 To unblock queueing of new deployments:
  `curl -d "" http://localhost:18080/management/switchboard?enable-deploy-queuing=ON`