updateFavourite = (project, add) ->
  endpoint =
    if add
      jsRoutes.controllers.DeployController.addFavourite(project)
    else
      jsRoutes.controllers.DeployController.deleteFavourite(project)

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
      updateFavourite(selectedProject, true)

  $('.delete-favourite-project-button').click (e) ->
    e.preventDefault()
    selectedProject = e.currentTarget.value
    if selectedProject?
      updateFavourite(selectedProject, false)
