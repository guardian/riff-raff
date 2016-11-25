$ ->
  updateOrAddParam = (url, param, value) ->
    re = new RegExp("([?|&])" + param + "=.*?(&|$)", "i")
    separator = if url.indexOf('?') != -1 then "&" else "?"
    encodedValue=encodeURIComponent(value)
    if url.match(re)
      url.replace(re,'$1'+param+'='+encodedValue+'$2')
    else
      url+separator+param+'='+encodedValue

  menu = null
  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      if menu?
        menu.destroy()
      menu = new BootstrapMenu('.history-context-menu', {
        fetchElementData: (rowElem) ->
          console.log 'doing this'
          {
            uuid: rowElem.data 'uuid'
            vcsName: rowElem.data 'vcsName'
            vcsUrl: rowElem.data 'vcsUrl'
            projectName: rowElem.data 'projectName'
            buildId: rowElem.data 'buildId'
            stage: rowElem.data 'stage'
          }
        actionsGroups: [
          ['viewLog', 'viewVcs', 'deployAgain', 'seeConfiguration'],
          ['filterProject', 'filterStage']
        ]
        actions: {
          viewLog: {
            name: "View log"
            onClick: (data) ->
              location.href = jsRoutes.controllers.DeployController.viewUUID(data.uuid).url
          }
          viewVcs: {
            name: (data) ->
              if data.vcsName?
                "View commit on " + data.vcsName
              else
                "View commit"
            isShown: (data) ->
              data.vcsUrl?
            onClick: (data) ->
              location.href = data.vcsUrl
          }
          deployAgain: {
            name: "Deploy again"
            onClick: (data) ->
              location.href = jsRoutes.controllers.DeployController.deployAgainUuid(data.uuid).url
          }
          seeConfiguration: {
            name: "See configuration"
            onClick: (data) ->
              location.href = jsRoutes.controllers.DeployController.deployConfig(data.projectName, data.buildId).url
          }
          filterStage: {
            name: (data) ->
              "Filter on Stage="+data.stage
            onClick: (data) ->
              location.href = updateOrAddParam location.href, 'stage', data.stage
          }
          filterProject: {
            name: (data) ->
              "Filter on Project="+data.projectName
            onClick: (data) ->
              location.href = updateOrAddParam location.href, 'projectName', data.projectName
          }
        }
      })
