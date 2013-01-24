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
    selectedProject = encodeURIComponent($(e.target).val())

  $('#buildInput').each ->
    input = $(this)
    serverUrl = input.data('url')
    input.autocomplete
      source: (request,response) ->
        $.getJSON(
          serverUrl+'/'+selectedProject,
          term: request.term.split( /,\s*/).pop(),
          response
        )
      open: (event,ui) -> menuOpen = true
      close: (event,ui) -> menuOpen = false
      minLength:0

  $('#buildInput').focus (e) ->
    if (!menuOpen)
      $(e.target).autocomplete("search")