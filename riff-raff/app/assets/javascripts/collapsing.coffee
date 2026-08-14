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

# Remembers which report-tree nodes the user has expanded/collapsed so that
# state survives the periodic ajax refresh of the log content, which
# otherwise re-renders the tree from scratch every time.
collapseState = {}

captureCollapseState = (content) ->
  content.find(".collapsing-node").each ->
    collapseState[$(this).attr('id')] = $(this).hasClass('in')

restoreCollapseState = (content) ->
  content.find(".collapsing-node").each ->
    id = $(this).attr('id')
    expanded = collapseState[id]
    if expanded?
      node = $(this)
      icon = $('#'+id+'-icon')
      if expanded
        node.addClass('in')
        icon.removeClass('glyphicon-chevron-right').addClass('glyphicon-chevron-down')
      else
        node.removeClass('in')
        icon.removeClass('glyphicon-chevron-down').addClass('glyphicon-chevron-right')

$ ->
  setupCallbacks()
  if (window.autoRefresh)
    window.autoRefresh.preRefresh (content) ->
      captureCollapseState(content)
    window.autoRefresh.postRefresh (content) ->
      restoreCollapseState(content)
      setupCallbacks()