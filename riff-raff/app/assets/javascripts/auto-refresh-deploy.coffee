// every second:
// - load the deploy progress
$.get(
  $(this).data('ajax-refresh'),
  (deployProgressHtml) ->
    // - for any running steps (elements matching "li > span > span.state-running"):
    $('li > span > span.state-running').each ->
      // - get the ID for the step
      // i.e. find the first <ul> in step.childNodes, then read its id
      for (let node of this.childNodes) {
        if (node.nodeName === 'UL') {
          const id = ul.getAttribute("id")
          if (id != null) {
            // - index the deploy progress html with the id for the step
            // i.e. const newElement = $(progress).find(s`li > #${stepId}`)
            const newElement = $(deployProgressHtml).find(s`li > #${stepId}`)
            // - replace the step html with the new html
            // i.e. this.replaceWith(newElement)
            this.replaceWith(newElement)
          }
          break;
        }
      }
)
