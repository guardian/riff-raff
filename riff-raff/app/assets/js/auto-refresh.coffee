jQuery ->
     setInterval ( ->
        $('[data-ajax-refresh]').each ->
           $(this).load($(this).data("ajax-refresh"))
     ), 1000

