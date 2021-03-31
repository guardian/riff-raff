const Surprise = {
    identifier: "april-fools",

    canRun: () => {
        const hasRun = !!localStorage.getItem(Surprise.identifier);
        if(hasRun) {
            return false;
        }

        const now = new Date();
        const isAprilFoolsDay = now.getDate() === 1 && now.getMonth() === 3;

        const isCode = location.hostname === "riffraff.code.dev-gutools.co.uk";
        const is31March = now.getDate() === 31 && now.getMonth() === 2;

        if (isCode && is31March) {
            return true;
        }

        return isAprilFoolsDay;
    },
    run: () => {
        const randomMultipleOfTen = (Math.floor(Math.random() * 36) + 1) * 10;
        const { body } = document;
        body.style.cssText = `transform: rotate(${randomMultipleOfTen}deg); pointer-events: none;`

        localStorage.setItem(Surprise.identifier, "true");

        const styles = [
            "color: red",
            "background: yellow",
            "font-size: 20px",
            "text-shadow: 2px 2px black",
        ].join(";");

        console.log("%c 🎉 APRIL FOOLS! (refresh the page...)", styles);
    }
}

document.addEventListener("DOMContentLoaded", function(){
  if(Surprise.canRun()) {
      Surprise.run();
  }
});
