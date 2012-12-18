updateOrAddParam = (url, param, value) ->
  re = new RegExp(param+"(.+?)(&|$)","g")
  pair = param+'='+value
  separator = if url.indexOf('?') != -1 then "&" else "?"
  if re.test(url)
    url.replace(re,pair)
  else
    url + separator + pair

filterByProjectName = ->
  projectName = $('#projectName').val()
  newUrl = updateOrAddParam(document.URL, "projectName", projectName)
  window.location = newUrl

$ ->
  $('.dropdown-toggle').dropdown()
  $('.dropdown input, .dropdown label').click((e) -> e.stopPropagation())
  $('#projectNameGo').click => filterByProjectName()
  $('#projectName').keypress((e) -> if e.which == 13 then filterByProjectName())