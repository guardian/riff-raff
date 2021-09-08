# Restricting deployments

## What are restrictions for?
Restrictions are principally designed to be used when there is a temporary issue with a project or environment, such that deploying would be dangerous or potentially degrade the product.

In addition they can be used when infrastructure security could be compromised by allowing any user to rollback or deploy a branch of their choice.

## Who can deploy a restricted project?
  - The author
  - Those in the restriction `allowlist`
  - Superusers

## Who can edit a restriction?
  - If the restriction is not locked, it is editable by all users.
  - If the restriction is locked, it is editable by the user that locked it and superusers. 

## Help! A colleague has left the Guardian and I need to edit their locked restriction!
Don't panic!

As noted above, superusers have the power to unlock restrictions.
Therefore, to solve this issue you simply need to ask a superuser to unlock the restriction.
Once done, you're able to edit the restriction as needed and if appropriate relock it.
