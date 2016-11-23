<!--- prev:terminology next:deploy.json -->
Anatomy of artifacts.zip
========================

**NOTE:** This is deprecated and should no longer be used.

The `artifacts.zip` file must have the layout described
here in order for magenta to understand it.

Here is an example `artifacts.zip` file:

    artifacts.zip
    |- deploy.json
    |- packages
       |- frontend-article
       |  `- frontend-article.jar
       `- frontend-static
          |- fonts/
          |- images/
          |- javascripts/
          `- stylesheets/


In this case we have two packages, one called `frontend-static` and one called
`frontend-article`. If we look in deploy.json you will find the metadata for
each of the packages. What we can see at the moment however are the files
that are associated with the metadata.

In the case of `frontend-article` there is one associated JAR file; whilst the
`frontend-static` package has a four directories that each contain files.