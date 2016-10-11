$ ->
  $(".stop-deploy-button").click (e) ->
    e.preventDefault()
    button = $(this)
    uuid = button.val()
    endpoint = jsRoutes.controllers.DeployController.stop(uuid)
    csrfTokenValue = button.siblings("input").val()
    $.ajax
      url: endpoint.url
      type: endpoint.method
      data: {csrfToken: csrfTokenValue}
      context: this
      success: ->
        $(".stop-deploy-button").addClass("disabled")
        $(".stop-deploy-button").html("Stopping Deploy...")

  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      if $(".ajax-refresh-disabled").length != 0
        $(".stop-deploy-hide").hide()
