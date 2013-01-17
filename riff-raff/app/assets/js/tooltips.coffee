$ ->
  $("[rel='tooltip']").tooltip()
  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      $("[rel='tooltip']").tooltip()