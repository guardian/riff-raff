updateOrAddParam = (url, param, value) ->
  re = new RegExp("([?|&])" + param + "=.*?(&|$)", "i")
  separator = if url.indexOf('?') != -1 then "&" else "?"
  encodedValue=encodeURIComponent(value)
  if url.match(re)
    url.replace(re,'$1'+param+'='+encodedValue+'$2')
  else
    url+separator+param+'='+encodedValue

getParamOrElse = (url, param, defaultValue) ->
  re = new RegExp(param+"=(.+?)(&|$)")
  match = re.exec(url)
  if match==null then defaultValue else match[1]

pushParam='tab'

updateAndPush = (event) ->
  console.log(event)
  newStage=event.currentTarget.id
  newURL = updateOrAddParam(document.URL, pushParam, newStage)
  window.history.pushState(null,null,newURL)

popstate = (event) ->
  currentStage = getParamOrElse(document.URL, pushParam, null)
  if currentStage!=null
    $('#'+currentStage).tab('show')

$ ->
  $(window).bind('popstate',popstate)
  $('#pushable a').click((e) -> updateAndPush(e))
  pushParam = $('#pushable').attr("push-param")