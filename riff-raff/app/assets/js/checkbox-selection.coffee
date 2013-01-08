$ ->
  window.autoRefresh.postRefresh ->
    $("#checkbox-select-all").click (e) ->
      e.preventDefault()
      $(".checkbox-select").prop('checked', true)
    $("#checkbox-select-none").click (e) ->
      e.preventDefault()
      $(".checkbox-select").prop('checked', false)
