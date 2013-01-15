$ ->
  $(".stop-deploy-button").click (e) ->
    e.preventDefault()
    uuid = $(this).val()
    jsRoutes.controllers.Deployment.stop(uuid).ajax
      context: this
      success: ->
        $(".stop-deploy-button").addClass("disabled")
        $(".stop-deploy-button").html("Stopping Deploy...")

  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      if $(".ajax-refresh-disabled").length != 0
        $(".stop-deploy-form").hide()
