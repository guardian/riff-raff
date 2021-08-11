$ ->
  $(".favourite-button").click (e) ->
    e.preventDefault()
    elemProjectInput = $('#projectInput')
    selectedProject = elemProjectInput.val()

    endpoint = jsRoutes.controllers.DeployController.favourite(selectedProject)
    csrfTokenValue = $('input[name="csrfToken"]')
    $.ajax
      url: endpoint.url
      type: endpoint.method
      data: {csrfToken: csrfTokenValue}
      context: this
