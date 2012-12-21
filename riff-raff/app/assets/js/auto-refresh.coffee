intervalId = null

scroll = ->
  if $(this).find(".ajax-refresh-scrollto").length == 1
    console.log("scrolling to bottom")
    $(this).find(".ajax-refresh-scrollto")[0].scrollIntoView()

enableRefresh = (interval=1000) ->
  disableRefresh()
  jQuery ->
    reload = ->
      $('[data-ajax-refresh]').each ->
        if $(".ajax-refresh-disabled").length == 0
          $(this).load($(this).data("ajax-refresh"))
          scroll()

    intervalId = setInterval reload, interval

    reload()

$ ->
  interval = if $('[data-ajax-interval]').length != 0 then $('[data-ajax-interval]').data("ajax-interval") else 1000
  console.log("Interval of "+interval)
  enableRefresh(interval)
