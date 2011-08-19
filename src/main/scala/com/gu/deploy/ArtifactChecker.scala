package com.gu.deploy

class ArtifactChecker(config: Config, username: String) {

  def checkApprovedStatus() {
    config.stage match {
      case "PROD" =>
        val approvedFileLocation = "/r2/ArtifactRepository/%s/approved-build-PROD" format config.appName

      case _ => // nothing to do
    }
  }

  /*
    def __init__(self, config, userName):
        self.config = config
        self.userName = userName

    def checkApprovedStatus(self):
        if self.config.stage == 'PROD' :
            approvedFileLocation = '/r2/ArtifactRepository/%s/approved-build-PROD' % self.config.type

            try :
                approvedProperties = executeandcheckremote(self.userName, 'svn.gudev.gnl', 'cat %s' % approvedFileLocation)
                approvedRelease = re.compile('^release=(\S*)', re.MULTILINE).search(approvedProperties).group(1)
                approvedBuild = re.compile('^build=(\S*)', re.MULTILINE).search(approvedProperties).group(1)

                if self.config.build != approvedBuild or self.config.branch != approvedRelease :
                    print('This release does not match the approved release %s build %s. Update the file %s on svn.gudev.gnl.' % (approvedRelease, approvedBuild, approvedFileLocation))
                else :
                    return True
            except Exception, e:
                print('Failed to get approved build from file %s. %s' % (approvedFileLocation, e))
        else :
            return True

        return False

   */
}