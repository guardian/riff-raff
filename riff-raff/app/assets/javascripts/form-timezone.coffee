$ ->
  if $('#timezoneInput').prop('selectedIndex') == 0
    myTimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
    $('#timezoneInput').val(myTimeZone)

