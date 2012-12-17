checkAndConfirm = ->
  if $('#stage').val() == "PROD"
    confirm("Digital Development is in change freeze until Monday the 7th of January 2013.\n\nAny deploys prior to this should be reviewed by your product manager or Mike Blakemore and discussed with Web Systems.\n\nPress OK if you still want to proceed or Cancel to abort.")
  else
    true

$ ->
  $('#modalConfirm').submit => checkAndConfirm()

