intervalId = null

callbackList = $.Callbacks()

visibleHeight = ->
  window.innerHeight || document.documentElement.clientHeight || document.body.clientHeight

bottomInView = (element) ->
  currentScroll = if (document.documentElement.scrollTop) then document.documentElement.scrollTop else document.body.scrollTop

  elementHeight = element.offsetHeight
  elementOffset = element.offsetTop
  totalHeight = elementOffset + elementHeight

  totalHeight - 60 <= currentScroll + visibleHeight()

scrollToBottom = (element) ->
  elementHeight = element.offsetHeight
  elementOffset = element.offsetTop
  totalHeight = elementOffset + elementHeight

  scrollTop = totalHeight - visibleHeight() + 60

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
          wrapper = $(this)
          loading = wrapper.find(".loading")
          err = wrapper.find(".error")

          loading.show()

          $(this).find(".content").load(
            $(this).data("ajax-refresh"),
            (response, status) ->
              loading.hide()
              if status == "success"
                err.hide()
                callbackList.fire()
                if divBottomWasInView && wrapper.data("ajax-autoscroll") == true
                  scrollToBottom(wrapper.get(-1))
              else
                err.show()
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

removePostRefreshCallBack = (callback) ->
  callbackList.remove callback

@autoRefresh = {
  postRefresh : addPostRefreshCallback
  remove: removePostRefreshCallBack
}