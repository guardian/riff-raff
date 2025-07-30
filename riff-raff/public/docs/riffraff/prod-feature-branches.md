Manually deploying feature branches to PROD
===========================================

Deploying feature branches (i.e. not `main`) to `PROD` is **strongly** discouraged due to:

1. **Increased risk**: deploying feature branches can bypass peer review and test automation, which significantly 
  increases risk for users and the organisation.
2. **Operational complexity**: most projects at The Guardian use continuous deployment (CD) to deploy the HEAD of `main`
  to `PROD`. Our tooling, processes and colleagues rely on this behaviour. For example, if you deploy a feature branch
  to `PROD` it might be accidentally overridden when another PR is merged. This creates unnecessary complexity.

In some rare scenarios, we might reasonably choose to deploy a feature branch despite these drawbacks. For example, if a
high priority application is experiencing a severity 1 incident and two engineers from the team who own the application
have paired on a fix, we might choose to deploy their branch (once built) rather than waiting for `main` to build after
merging the PR. This should only be done when the team is in agreement that the benefits outweigh the risks.

In rare scenarios like this we might also need to take additional actions, such as sending comms to other engineers who
might be working on the service or temporarily pausing CD. We should also aim to restore normal operations as quickly
as possible (i.e. re-enable CD, merge the change and allow Riff-Raff to deploy `main` to `PROD` as soon as this is safe).