Notification.requestPermission()

intervalId = null

checkStatus = () ->
  if $('[data-run-state]').length != 0
    buildName = window.riffraff.buildName
    switch $('[data-run-state]').data('run-state')
      when 'Failed' then new Notification('Riffraff', {body: 'Deployment of ' + buildName + ' has failed!'})
      when 'Completed' then new Notification('Riffraff', {body: 'Deployment of ' + buildName + ' has finished'})
    disableCheck()

if $('[data-run-state]').length == 0
  intervalId = setInterval checkStatus, 800

disableCheck = ->
  clearInterval(intervalId) if intervalId?