$ ->
  $("#tagInput").prop('disabled', !$("#trigger_2").prop("checked"))
  $("input[data-radio='trigger']").change ->
    $("#tagInput").prop('disabled', $(this).val() != "2")
