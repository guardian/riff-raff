updateOrAddParam = (location, param, value) ->
  re = new RegExp("([?|&])" + param + "=.*?(&|$)", "i")
  separator = if location.search == "" then "?" else "&"
  encodedValue=encodeURIComponent(value)
  queryString =
    if location.search.match(re)
      location.search.replace(re,'$1'+param+'='+encodedValue+'$2')
    else
      location.search + separator + param+'='+encodedValue
  location.pathname + queryString + location.hash

filterByProjectName = ->
  projectName = $('#projectInput').val()
  newUrl = updateOrAddParam(document.location, "projectName", projectName)
  pageResetUrl = updateOrAddParam(newUrl, "page", "1")
  window.location = pageResetUrl

$ ->
  $('.dropdown-toggle').dropdown()
  $('.dropdown input, .dropdown label').click((e) -> e.stopPropagation())
  # don't hide panel when autocomplete is clicked
  $('body').on('click', '.ui-autocomplete', (e) -> e.stopPropagation())
  $('#projectNameGo').click => filterByProjectName()
  $('#projectInput').keypress((e) -> if e.which == 13 then filterByProjectName())
