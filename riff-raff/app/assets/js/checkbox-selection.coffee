$ ->
  window.autoRefresh.postRefresh ->
    $('*[data-checkbox-target]').click (e) ->
      e.preventDefault()
      target = $(this).data("checkbox-target")
      $(target).prop('checked', $(this).data("checkbox-state"))
