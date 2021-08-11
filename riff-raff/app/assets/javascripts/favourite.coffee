updateFavourite = (project) ->
  endpoint = jsRoutes.controllers.DeployController.favourite(project)
  csrfTokenValue = $('input[name="csrfToken"]').val()
  $.ajax
    url: endpoint.url
    type: endpoint.method
    data: {csrfToken: csrfTokenValue}
    context: this
    success: ->
      location.reload();

$ ->
  $('#add-favourite-project-button').click (e) ->
    e.preventDefault()

    elemProjectInput = $('#projectInput')
    selectedProject = elemProjectInput.val()

    if selectedProject?
      updateFavourite(selectedProject)

  $('.delete-favourite-project-button').click (e) ->
    e.preventDefault()
    selectedProject = e.target.parentElement.value
    updateFavourite(selectedProject)
