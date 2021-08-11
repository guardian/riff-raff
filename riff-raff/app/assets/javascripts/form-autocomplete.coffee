selectedProject = ''
menuOpen = false

updateBuildInfo = (buildNumber) ->
  $('#build-info').load(jsRoutes.controllers.DeployController.buildInfo(selectedProject, buildNumber).url)

updateDeployInfo = () ->
  elemProjectInput = $('#projectInput')
  isExactMatch = elemProjectInput.hasClass("project-exact-match")
  selectedProject = elemProjectInput.val()
  selectedStage = $('#stage').val()

  url = if selectedStage == ''
          jsRoutes.controllers.DeployController.deployHistory(selectedProject, undefined, isExactMatch).url
        else
          jsRoutes.controllers.DeployController.deployHistory(selectedProject, selectedStage, isExactMatch).url
  $('#deploy-info').load(
    url,
    ->
      $('tbody.rowlink').rowlink()
      $("[rel='tooltip']").tooltip()
  )

$ ->
  $('#projectInput').each ->
    input = $(this)
    serverUrl = input.data('url')
    input.autocomplete
      source:serverUrl
      minLength:0

  $('#projectInput').blur updateDeployInfo

  $('#buildInput').each ->
    input = $(this)
    serverUrl = input.data('url')
    input.autocomplete
      source: (request,response) ->
        $.getJSON(
          serverUrl,
          {term: request.term.split( /,\s*/).pop(), project: selectedProject},
          response
        )
      open: (event,ui) -> menuOpen = true
      close: (event,ui) ->
        menuOpen = false
        updateBuildInfo( input.val() )
      select: (event,ui) ->
        updateBuildInfo( input.val() )
      minLength:0

  $('#buildInput').on('input keyup',
    ->
      input = $(this)
      updateBuildInfo( input.val() )
  )

  $('#buildInput').focus (e) ->
    if (!menuOpen)
      $(e.target).autocomplete("search")

  $('#stage').change ->
    updateDeployInfo()

  $('.select-favourite-project-button').click (e) ->
    e.preventDefault()
    project = e.target.value

    elemProjectInput = $('#projectInput')
    elemProjectInput.val(project)
    updateDeployInfo()

  updateDeployInfo()

  console.log('initialised')
