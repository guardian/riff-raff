intervalId = null

enableRefresh = ->
  disableRefresh()
  jQuery ->
    intervalId = setInterval ( ->
      $('[data-ajax-refresh]').each ->
        $(this).load($(this).data("ajax-refresh"))
    ), 1000

disableRefresh = ->
  clearInterval(intervalId) if intervalId?

@ajaxRefresh = {
  enable : enableRefresh
  disable : disableRefresh
}

