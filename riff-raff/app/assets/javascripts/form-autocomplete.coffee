selectedProject = ''
menuOpen = false

updateBuildInfo = (buildNumber) ->
  $('#build-info').load(jsRoutes.controllers.DeployController.buildInfo(selectedProject, buildNumber).url)

updateStageInfo = () ->
  elemProjectInput = $('#projectInput')
  isExactMatch = elemProjectInput.hasClass("project-exact-match")
  selectedProject = elemProjectInput.val()
  selectedBuild = $('#buildInput').val()

  url = jsRoutes.controllers.DeployController.allowedStages(selectedProject, selectedBuild).url

  $.ajax({
    url: url,
    dataType: 'html',
    success: (stageOptions) ->
      stageInput = $('#stage')
      stageInput.html(stageOptions)
      stageInput.prop('disabled', false)
      updateDeployInfo()
  });

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

readFavourites = () ->
  JSON.parse(localStorage.getItem('favouriteProjects'))

writeFavourites = (newFavourites) ->
  localStorage.setItem('favouriteProjects', JSON.stringify(newFavourites))

addFavourite = (project) ->
  favourites = readFavourites()
  newFavourites =
    if favourites?
      projectAlreadyFavourited = favourites.includes(project)
      if !projectAlreadyFavourited
        favourites.push(project)

      favourites
    else
      [project]
  writeFavourites(newFavourites)
  renderFavourites()

deleteFavourite = (project) ->
  favourites = readFavourites()
  newFavourites =
    if favourites?
      favourites.filter (fav) -> fav != project
    else
      []
  writeFavourites(newFavourites)
  renderFavourites()

setupFavouriteHandlers = () ->
  $('.delete-favourite-project-button').click (e) ->
    e.preventDefault()
    selectedProject = e.currentTarget.value
    if selectedProject?
      deleteFavourite(selectedProject)

  $('.select-favourite-project-button').click (e) ->
    e.preventDefault()
    project = e.target.value

    elemProjectInput = $('#projectInput')
    elemProjectInput.val(project)

    $('#buildInput').val('') # clear build input when project changed
    updateStageInfo()

renderFavourites = () ->
  container = $('#favourites-container')
  favourites = readFavourites()
  if favourites? && favourites.length > 0
    container.removeClass('hidden')
    list = $('#favourites-list', container)
    list.empty()
    favourites.forEach (fav) ->
      list.append("
          <div class=\"favourite\">
            <button class=\"select-favourite-project-button btn btn-default\" value=\"#{fav}\" aria-label=\"Use favourite: #{fav}\" title=\"Use favourite: #{fav}\">#{fav}</button>
            <button class=\"delete-favourite-project-button btn btn-xs btn-danger\" value=\"#{fav}\" aria-label=\"Delete favourite: #{fav}\" title=\"Delete favourite: #{fav}\">
              <i class=\"glyphicon glyphicon-trash glyphicon glyphicon-white\"></i>
            </button>
          </div>
      ")
    setupFavouriteHandlers()
  else
    container.addClass('hidden')

$ ->
  $('#projectInput').each ->
    input = $(this)
    serverUrl = input.data('url')
    input.autocomplete
      source:serverUrl
      minLength:0

    addFavouriteProjectButton = $('#add-favourite-project-button')
    updateFavouriteButton = ->
      projectInputIsEmpty = input.val().trim() == ''
      addFavouriteProjectButton.prop('disabled', projectInputIsEmpty)

    input.on('change', updateFavouriteButton)
    input.on('keyup', updateFavouriteButton)

  $('#projectInput').blur -> updateDeployInfo()

  $('#projectInput').change -> $('#buildInput').val('') # clear build input when project changed

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
        updateStageInfo()
      select: (event,ui) ->
        updateBuildInfo( input.val() )
        updateStageInfo()
      minLength:0

  $('#buildInput').on('input',
    ->
      input = $(this)
      updateBuildInfo( input.val() )
      updateStageInfo()
      updateDeployInfo()
  )

  $('#buildInput').focus (e) ->
    if (!menuOpen)
      $(e.target).autocomplete("search")

  $('#buildInput').blur -> updateDeployInfo()

  $('#stage').change -> updateDeployInfo()

  updateDeployInfo()

  $('#add-favourite-project-button').click (e) ->
    e.preventDefault()

    elemProjectInput = $('#projectInput')
    selectedProject = elemProjectInput.val()

    if selectedProject
      addFavourite(selectedProject)

  renderFavourites()

  console.log('initialised')
