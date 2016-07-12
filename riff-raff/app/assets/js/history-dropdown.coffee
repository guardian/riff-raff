updateOrAddParam = (queryString, param, value) ->
  re = new RegExp("([?|&])" + param + "=.*?(&|$)", "i")
  separator = if queryString == "" then "?" else "&"
  encodedValue = encodeURIComponent(value)
  if queryString.match(re)
    queryString.replace(re,'$1'+param+'='+encodedValue+'$2')
  else
    queryString + separator + param+'='+encodedValue

filterByProjectName = ->
  projectName = $('#projectInput').val()
  newQueryString = updateOrAddParam(window.location.search, "projectName", projectName)
  pageResetQueryString = updateOrAddParam(newQueryString, "page", "1")
  window.location.search = pageResetQueryString

$ ->
  $('.dropdown-toggle').dropdown()
  $('.dropdown input, .dropdown label').click((e) -> e.stopPropagation())
  # don't hide panel when autocomplete is clicked
  $('body').on('click', '.ui-autocomplete', (e) -> e.stopPropagation())
  $('#projectNameGo').click => filterByProjectName()
  $('#projectInput').keypress((e) -> if e.which == 13 then filterByProjectName())
