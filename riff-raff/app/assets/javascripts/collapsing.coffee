setIcon = (id, expanded) ->
  icon = $('#'+id+'-icon')
  if expanded
    icon.removeClass('glyphicon-chevron-right').addClass('glyphicon-chevron-down')
  else
    icon.removeClass('glyphicon-chevron-down').addClass('glyphicon-chevron-right')

# Remembers only nodes the user has explicitly expanded/collapsed (via a
# click, not the tree's default open/closed state), so that choice survives
# the periodic ajax refresh of the log content, which otherwise re-renders
# the whole tree - and its default state - from scratch every time.
userToggledState = {}

setupCallbacks = ->
  $(".collapsing-node").on 'show.bs.collapse', (e) ->
    userToggledState[e.target.id] = true
    setIcon(e.target.id, true)
  $(".collapsing-node").on 'hide.bs.collapse', (e) ->
    userToggledState[e.target.id] = false
    setIcon(e.target.id, false)

applyUserToggledState = ->
  $(".collapsing-node").each ->
    id = $(this).attr('id')
    expanded = userToggledState[id]
    if expanded?
      if expanded then $(this).addClass('in') else $(this).removeClass('in')
      setIcon(id, expanded)

$ ->
  setupCallbacks()
  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      applyUserToggledState()
      setupCallbacks()