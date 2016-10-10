$ ->
  $("#enable-notifications").click (e) ->
    e.preventDefault()
    Notification.requestPermission()

intervalId = null

checkStatus = () ->
  if $('[data-run-state]').length != 0
    buildName = window.riffraff.buildName
    switch $('[data-run-state]').data('run-state')
      when 'Failed' then new Notification('Riffraff', {body: 'Deployment of ' + buildName + ' failed!'})
      when 'Completed' then new Notification('Riffraff', {body: 'Deployment of ' + buildName + ' is finished'})
    disableCheck()

if $('[data-run-state]').length == 0
  intervalId = setInterval checkStatus, 1000

disableCheck = ->
  clearInterval(intervalId) if intervalId?