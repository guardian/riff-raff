notify = (message) ->
  new Notification('Riffraff', {body: message})
  disableCheck()

checkStatus = () ->
  if $('[data-run-state]').length != 0
    buildName = window.riffraff.buildName
    stage = window.riffraff.stage
    buildId = window.riffraff.buildId
    switch $('[data-run-state]').data('run-state')
      when 'Failed' then notify('❌ Deployment of ' + buildName + ' (' + buildId + ')' + ' in ' + stage + ' has failed!')
      when 'Completed' then notify('👍 Deployment of ' + buildName + ' (' + buildId + ')' + ' in ' + stage + ' has succeeded')

if !window.riffraff.isDone && window.autoRefresh
  Notification.requestPermission()
  window.autoRefresh.postRefresh checkStatus

disableCheck = ->
  if window.autoRefresh
    window.autoRefresh.remove checkStatus