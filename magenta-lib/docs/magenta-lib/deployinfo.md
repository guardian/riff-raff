Deployment Information
======================

Magenta sources deployment information about the infrastructure by parsing a deployinfo JSON document.

The source of this file depends on the app you are using, the the CLI currently obtains the document by executing
the `/opt/bin/deployinfo.json` script on the server you run it on.  This script compiles a mix of EC2 and fixed data
to create a set of deployment data.

The file has two top level keys: `hosts` and `data`.

Hosts section
-------------

This section associates hosts with applications and stages.  Other fields are in the JSON but not currently used in
 anger within magenta.

When deploying an artifact to a given stage, magenta will filter this list against the specified stage and the set of
 applications it has discovered in the artifact's `deploy.json`.

Data section
------------

Other pieces of information are often needed when deploying.  This is handled by providing a key-value lookup whose
values vary given different application and stage combinations.  In the example below, one of the available keys is
`ddm`, used by applications that need to push static files onto a docroot manager (DDM).  Each stage has a single DDM
for all apps that want to use it - tasks can look up the correct value for a key given an app and stage.

Another example is the `credentials:aws` key.  This is used by the AWS tasks for authenticating against the AWS APIs.
The `credentials:` form is special and will be used to form a key-ring passed to deployments. The value is only the user
id, the secret is stored in the Riff-Raff configuration by looking up the property `credentials.<service>.<userid>`
(service would be aws and userid would be the value looked up from deployinfo as explained below. At present, CLI
deploys cannot use this form of authentication.

Each line in a key has regular expressions for both `app` and `stage` (these must match the whole app or stage name)
along with the `value` that should be returned if the regular expression matches and an optional `comment` field to
describe that particular value.  In the case that two or more sets of regular expressions match, the value from the
first in the list will be used.  This means that you can have a catch-all value at the end (as seen in the
`credentials:aws` example).

    {
        "hosts": [
            {
                "app": "contentapi::mq",
                "created_at": "Tue Jul 31 11:49:23 UTC 2012",
                "dnsname": "ec2-54XXXXXXXX.eu-west-1.compute.amazonaws.com",
                "group": "eu-west-1b",
                "hostname": "ec2-54XXXXXXXX.eu-west-1.compute.amazonaws.com",
                "instancename": "i-XXXXXXXX",
                "internalname": "ip-10-XXXXXXXX.eu-west-1.compute.internal",
                "stage": "CODE"
            },
            {
                "app": "contentapi::mq",
                "created_at": "Fri Aug 17 08:02:04 UTC 2012",
                "dnsname": "ec2-46XXXXXXXX.eu-west-1.compute.amazonaws.com",
                "group": "eu-west-1a",
                "hostname": "ec2-46XXXXXXXX.eu-west-1.compute.amazonaws.com",
                "instancename": "i-XXXXXXXX",
                "internalname": "ip-10-XXXXXXXX.eu-west-1.compute.internal",
                "stage": "PROD"
            },
            {
                "app": "contentapi",
                "group": "0",
                "hostname": "codslrmst01.gudev.gnl",
                "stage": "CODE"
            },
            {
                "app": "contentapi",
                "group": "0",
                "hostname": "slrmst01.gul3.gnl",
                "stage": "PROD"
            },
            {
                "app": "contentapi",
                "group": "1",
                "hostname": "slrmst51.gutc.gnl",
                "stage": "PROD"
            },
            {
                "app": "api-indexer",
                "group": "0",
                "hostname": "codconupl01.gudev.gnl",
                "stage": "CODE"
            },
            {
                "app": "api-indexer",
                "group": "0",
                "hostname": "conupl01.gul3.gnl",
                "stage": "PROD"
            },
            {
                "app": "api-indexer",
                "group": "1",
                "hostname": "conupl51.gutc.gnl",
                "stage": "PROD"
            },
        ],
        "data": {
            "aws-bucket": [
                {
                    "app": "pasteup",
                    "stage": "CODE",
                    "value": "pasteup-bucket-name-for-code"
                }
            ],
            "credentials:aws": [
                {
                    "app": "frontend::.*",
                    "comment": "gu-aws-frontend riff-raff",
                    "stage": ".*",
                    "value": "XXXXXXXXXXXXXXXXXXXX"
                },
                {
                    "app": "ophan-.*",
                    "comment": "arn:aws:iam::NNNNNNNNNNNN:user/magenta",
                    "stage": ".*",
                    "value": "YYYYYYYYYYYYYYYYYYYY"
                },
                {
                    "app": ".*",
                    "comment": "arn:aws:iam::NNNNNNNNNNNN:user/riff-raff",
                    "stage": ".*",
                    "value": "ZZZZZZZZZZZZZZZZZZZZ"
                }
            ],
            "ddm": [
                {
                    "app": ".*",
                    "stage": "CODE",
                    "value": "ddm.gucode.gnl"
                },
                {
                    "app": ".*",
                    "stage": "QA",
                    "value": "ddm.guqa.gnl"
                },
                {
                    "app": ".*",
                    "stage": "RELEASE",
                    "value": "ddm.gurelease.gnl"
                },
                {
                    "app": ".*",
                    "stage": "TEST",
                    "value": "ddm.gutest.gnl"
                },
                {
                    "app": ".*",
                    "stage": "STAGE",
                    "value": "ddm.gustage.gnl"
                },
                {
                    "app": ".*",
                    "stage": "PROD",
                    "value": "ddm.guprod.gnl"
                }
            ]
        }
    }