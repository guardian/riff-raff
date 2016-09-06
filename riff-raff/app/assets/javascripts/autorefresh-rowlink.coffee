$ ->
  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      $('tbody.rowlink').rowlink()