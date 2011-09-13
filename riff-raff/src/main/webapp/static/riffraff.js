$(document).ready(function() {
    //
    // Drop down menu support
    //
    $("body").bind("click", function (e) {
        $('.dropdown-toggle, .menu').parent("li").removeClass("open");
    });
    $(".dropdown-toggle, .menu").click(function (e) {
        var $li = $(this).parent("li").toggleClass('open');
        return false;
    });
});