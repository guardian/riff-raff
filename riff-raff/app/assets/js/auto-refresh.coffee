intervalId = null

enableRefresh = (interval=1000) ->
  disableRefresh()
  jQuery ->
    reload = ->
      $('[data-ajax-refresh]').each ->
        $(this).load($(this).data("ajax-refresh"))

    intervalId = setInterval reload, interval

    reload()

disableRefresh = ->
  clearInterval(intervalId) if intervalId?

@ajaxRefresh = {
  enable : enableRefresh
  disable : disableRefresh
}

