setupCallbacks = ->
  console.log 'setting up'
  $(".collapsing-node").on 'show.bs.collapse', (e) ->
    iconId = e.target.id+'-icon'
    element = $('#'+iconId)
    element.removeClass('glyphicon-chevron-right')
    element.addClass('glyphicon-chevron-down')
  $(".collapsing-node").on 'hide.bs.collapse', (e) ->
    iconId = e.target.id+'-icon'
    element = $('#'+iconId)
    element.removeClass('glyphicon-chevron-down')
    element.addClass('glyphicon-chevron-right')


$ ->
  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      setupCallbacks()