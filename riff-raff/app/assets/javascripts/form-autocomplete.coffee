selectedProject = ''
menuOpen = false

updateBuildInfo = (buildNumber) ->
  $('#build-info').load(jsRoutes.controllers.DeployController.buildInfo(selectedProject, buildNumber).url)

$ ->
  $('#projectInput').each ->
    input = $(this)
    serverUrl = input.data('url')
    input.autocomplete
      source:serverUrl
      minLength:0

  $('#projectInput').blur (e) ->
    selectedProject = $(e.target).val()
    $('#deploy-info').load(
      jsRoutes.controllers.DeployController.projectHistory(selectedProject).url,
      ->
        $(".promoteDeploy").click (e) ->
          (e).preventDefault()
          buildId = $(this).data("build-id")
          stage = $(this).data("stage")
          $('#buildInput').val(buildId)
          $('#stage').val(stage)
          mixpanel? && mixpanel.track "Deploy promoted", { projectInput: selectedProject, promotedStage: stage, promotedBuildId: buildId}
        $("[rel='tooltip']").tooltip()
    )

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