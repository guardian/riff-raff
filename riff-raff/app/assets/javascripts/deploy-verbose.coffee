testSameOrigin = (url) ->
  if url == null
    true
  else
    loc = window.location
    a = document.createElement('a')
    a.href = url
    a.hostname == loc.hostname && a.port == loc.port && a.protocol == loc.protocol

updateCSS = (selector, styles) ->
  for sheet in document.styleSheets
    if testSameOrigin(sheet.href)
      for rule in sheet.cssRules || sheet.rules || []
        if rule.selectorText == selector
          for style, value of styles
            rule.style[style] = value

updateOrAddParam = (url, param, value) ->
  re = new RegExp("([?|&])" + param + "=.*?(&|$)", "i")
  separator = if url.indexOf('?') != -1 then "&" else "?"
  encodedValue=encodeURIComponent(value)
  if url.match(re)
    url.replace(re,'$1'+param+'='+encodedValue+'$2')
  else
    url+separator+param+'='+encodedValue

getParamOrElse = (url, param, defaultValue) ->
  re = new RegExp(param+"=(.+?)(&|$)")
  match = re.exec(url)
  if match==null then defaultValue else match[1]

enableVerbose = ->
  updateCSS("span.message-verbose",{'display':'list-item'})

disableVerbose = ->
  updateCSS("span.message-verbose",{'display':'none'})

setVerbose = (visible) ->
  if (visible)
    enableVerbose()
  else
    disableVerbose()

initialise = ->
  setVerbose($('#verbose-checkbox').is(':checked'))

updateAndPush = ->
  newState = $('#verbose-checkbox').is(':checked')
  setVerbose(newState)
  verboseParam = if newState then '1' else '0'
  newURL = updateOrAddParam(document.URL, 'verbose', verboseParam)
  window.history.pushState(null,null,newURL)
  mixpanel? && mixpanel.track "Verbose toggled", {"verbose": newState}
  true

popstate = (event) ->
  verbose = getParamOrElse(document.URL, 'verbose', '0')=='1'
  setVerbose(verbose)
  $('#verbose-checkbox').prop('checked', verbose)

$ ->
  $(window).bind('popstate',popstate)
  $('#verbose-checkbox').click => updateAndPush()
  setVerbose($('#verbose-checkbox').is(':checked'))