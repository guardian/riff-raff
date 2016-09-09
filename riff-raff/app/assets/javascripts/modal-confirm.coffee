clickFromModalDialog = false

bindToButtons = ->
  $('#modalConfirm').click =>
    if clickFromModalDialog
      true
    else
      stageList = $('#freezeModal').data("stages").split(",")
      if jQuery.inArray($('#stage').val(), stageList) >= 0
        $('#freezeModal').modal()
        false
      else
        true

  $('#freezeProceed').click =>
    clickFromModalDialog = true
    $('#modalConfirm').click()

  $('#freezeCancel').click =>
    $('#freezeModal').modal('hide')

$ ->
  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      bindToButtons()
  else
    bindToButtons()
