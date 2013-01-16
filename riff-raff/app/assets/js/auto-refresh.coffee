intervalId = null

callbackList = $.Callbacks()

bottomInView = (element) ->
  currentScroll = if (document.documentElement.scrollTop) then document.documentElement.scrollTop else document.body.scrollTop

  elementHeight = element.offsetHeight
  elementOffset = element.offsetTop
  totalHeight = elementOffset + elementHeight
  visibleHeight = document.documentElement.clientHeight

  totalHeight - 60 <= currentScroll + visibleHeight

scrollToBottom = (element) ->
  elementHeight = element.offsetHeight
  elementOffset = element.offsetTop
  totalHeight = elementOffset + elementHeight
  visibleHeight = document.documentElement.clientHeight

  scrollTop = totalHeight - visibleHeight + 60

  $('html, body').animate(
    { scrollTop: scrollTop },
    200,
    "easeOutQuint"
  )

enableRefresh = (interval=1000) ->
  disableRefresh()
  jQuery ->
    reload = ->
      $('[data-ajax-refresh]').each ->
        if $(".ajax-refresh-disabled").length == 0
          divBottomWasInView = bottomInView($(this).get(-1))
          $(this).load(
            $(this).data("ajax-refresh"),
            ->
              callbackList.fire()
              if divBottomWasInView && $(this).data("ajax-autoscroll") == true
                scrollToBottom($(this).get(-1))
          )

    intervalId = setInterval reload, interval

    reload()

disableRefresh = ->
  clearInterval(intervalId) if intervalId?

$ ->
  interval = if $('[data-ajax-interval]').length != 0 then $('[data-ajax-interval]').data("ajax-interval") else 1000
  enableRefresh(interval)

addPostRefreshCallback = (callback) ->
  callbackList.add callback

@autoRefresh = {
  postRefresh : addPostRefreshCallback
}