intervalId = null

enableRefresh = (interval=1000) ->
  disableRefresh()
  jQuery ->
    intervalId = setInterval ( ->
      $('[data-ajax-refresh]').each ->
        $(this).load($(this).data("ajax-refresh"))
    ), interval

disableRefresh = ->
  clearInterval(intervalId) if intervalId?

@ajaxRefresh = {
  enable : enableRefresh
  disable : disableRefresh
}

