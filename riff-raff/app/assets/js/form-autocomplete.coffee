selectedProject = ''
menuOpen = false

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
      jsRoutes.controllers.Deployment.projectHistory(selectedProject).url,
      ->
        $(".promoteDeploy").click (e) ->
          (e).preventDefault()
          $('#buildInput').val($(this).data("build-id"))
          $('#stage').val($(this).data("stage"))
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
      close: (event,ui) -> menuOpen = false
      minLength:0

  $('#buildInput').focus (e) ->
    if (!menuOpen)
      $(e.target).autocomplete("search")