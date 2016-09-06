graph = (args) ->

#  Monkey patch for Safari 5 (editorial macs)
  if (!Function.prototype.bind)
    Function.prototype.bind = (context) ->
      fn = this
      () -> fn.apply(context, arguments)

  params = window.location.search

  if params.indexOf '?' != -1
    params = params.substring 1

  container = document.getElementById(args.container_id)

  dataURL = "#{args.data_path}?#{params}&callback=?"

  element = container.querySelector('.graph')

  graphHeight = args.height

  unless graphHeight?
    legendHeight = 85
    stuffBeforeGraph = $(container).position().top
    graphHeight = $(window).height() - legendHeight - stuffBeforeGraph

  graphWidth = args.width

  $(element).height(graphHeight)

  graphArgs = {
    element: element
    width: graphWidth
    stroke: true
    strokeWidth: 2
    renderer: args.renderer || 'area'
  }

  tidyData = (data) ->
    Rickshaw.Series.zeroFill(data)
    palette = new Rickshaw.Color.Palette(
      scheme: args.colors || ['#4572A7', '#AA4643', '#89A54E', '#80699B', '#3D96AE', '#92A8CD', '#A47D7C', '#DB843D', '#B5CA92']
    )
    statepalette = { "Completed": '#468847', "Failed": '#B94A48', "Running": '#3A87AD', "Not running": '#999999'}

    for series in data
      series.color = statepalette[series.deploystate] || palette.color()

    data

  $.getJSON dataURL, (data) ->
    graphArgs.series = tidyData(data.response.series)
    # console.log(graphArgs.series)

    # first time creation
    unless graphWidth?
      labelLength = 0
      for series in graphArgs.series
        nameLength = series.name.length
        if nameLength > labelLength
          labelLength = nameLength
      legendWidth = labelLength * 6.5 + 50
      # console.log(legendWidth)
      # graphMargins = $(container).position().left
      # console.log(graphMargins)
      graphWidth = $(container).width() - legendWidth - 50
      # console.log(graphWidth)

    graphArgs.width = graphWidth

    graph = new Rickshaw.Graph(graphArgs)

    domain = graph.x.domain()
    rangeSeconds = domain[1] - domain[0]
    hour = 60 * 60
    day = 24 * hour
    week = 7 * day
    monthish = 30.5 * day

    new Rickshaw.Graph.Axis.Time(
      graph: graph
      timeUnit:
        seconds: (unit for unit in [monthish, week, day, hour] when Math.floor(rangeSeconds / unit) >= 2)[0]
        formatter: (d) -> moment(d).format("Do MMM")
    )

    new Rickshaw.Graph.Axis.Y(
      graph: graph
      orientation: 'left'
      tickFormat: Rickshaw.Fixtures.Number.formatKMBT
      element: container.querySelector('.graph-y-axis')
    )

    new Rickshaw.Graph.HoverDetail(
      graph: graph
      formatter: (series, x, y) ->
        date = '<span class="date">' + moment(x*1000).format("ddd Do MMM") + '</span>'
        deploys = '<span class="deploys">' + series.name + ': ' + y + '</span>'
        date + '<br/>' + deploys
    )

    legend = new Rickshaw.Graph.Legend(
      graph: graph
      element: container.querySelector('.graph-legend')
      naturalOrder: true
    )

    new Rickshaw.Graph.Behavior.Series.Toggle(
      graph: graph
      legend: legend
    )

    new Rickshaw.Graph.Behavior.Series.Highlight(
      graph: graph
      legend: legend
    )

    graph.update()

    refreshGraph = ->
      $.getJSON dataURL, (data) ->
        # so, the graph object copies a reference to graphArgs.series - we must update
        # that object not replace it
        # my original code here was: graphArgs.series = tidyData(data) but that didn't
        # work becasue it created a new series object
        for series, i in tidyData(data.response.series)
          graphArgs.series[i].data = series.data

        graph.render()

    setInterval(refreshGraph, 60000) unless window.location.search.indexOf("no_refresh") != -1

# this doesn't quite work alas
#    $(window).resize ->
#      setTimeout(location.reload, 50)


window.graph = graph