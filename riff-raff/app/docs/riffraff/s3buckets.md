Riff-Raff, AWS credentials and S3 uploads
=========================================

Riff-raff needs to talk to AWS from time to time.  At the time of writing this is for uploading artifacts and static
 files to S3 buckets.

Telling riff-raff about S3 credentials
--------------------------------------

There are two steps to let riff-raff know about a new set of credentials.  The secret access key is stored in the
properties file managed by websys, keyed from the access key.

Riff-raff decides which access key to use based on information available in the deployment info json file that is
parsed at runtime.  This file contains a host section, but also a key section - like you see below.

    {
        "hosts":[
            {"group":"a", "stage":"CODE", "app":"microapp-cache", "hostname":"machost01.dc-code.gnl"},
            {"group":"b", "stage":"CODE", "app":"microapp-cache", "hostname":"machost51.dc-code.gnl"},
            {"group":"a", "stage":"QA", "app":"microapp-cache", "hostname":"machost01.dc-qa.gnl"}
        ],
        "keys":[
            {"app":"microapp-cache", "stage":"CODE", "accesskey":"AAA"},
            {"app":"frontend-article", "stage":"CODE", "accesskey":"CCC"},
            {"app":"frontend-.*", "stage":"CODE", "accesskey":"BBB"},
            {"app":"frontend-gallery", "stage":"CODE", "accesskey":"SHADOWED"},
            {"app":".*", "stage":".*", "accesskey":"DDD"}
        ]
    }

The key section is processed in order and the first match for the app being deployed and the stage being deployed to
is used.  The app and stage values are regular expressions.  Note that order is important - in the above example
frontend-gallery being deployed to CODE will use key BBB, rather than SHADOWED.

Allowing riff-raff access to your bucket
----------------------------------------

When you need riff-raff to upload files to a bucket as part of a deployment you will need to add a bucket policy to
the buckets in question.

In the S3 AWS console you can do this by selecting properties for the bucket and Adding or editing the bucket policy.
 The sample below should suffice, you can get the Principal (accountID and userName) from the IAM console.  You can
 also look at the deployment information page as the comment should be filled in the the riff-raff user under Keys.

    {
        "Version": "2008-10-17",
        "Id": "riff-raff-bucket-policy",
        "Statement": [
            {
                "Sid": "S3-bucket-policy",
                "Effect": "Allow",
                "Principal": {
                    "AWS": "arn:aws:iam::<accountID>:user/<userName>"
                },
                "Action": "s3:Put*",
                "Resource": "arn:aws:s3:::<bucketName>/*"
            }
        ]
    }

In order for this to work, the riff-raff user also has an IAM policy that allows it to put objects into any bucket - see
 [amazon's docs](http://docs.amazonwebservices.com/IAM/latest/UserGuide/Delegation.html) for more information.