processDates = ->
  $('time.makeRelativeDate').each ->
    element = $(this)
    thisMoment = moment(element.attr('datetime'))
    relativeDate = thisMoment.fromNow()
    actualDate = thisMoment.format('Do MMM YYYY H:mm:ss')
    element.html("<span title='#{actualDate}'>#{relativeDate}</span>")

$ ->
  processDates()
  if (window.autoRefresh)
    window.autoRefresh.postRefresh ->
      processDates()