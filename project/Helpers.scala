object Helpers {

  // TODO is this actually needed?
  implicit class RichString(val s: String) extends AnyVal {
    def dequote = s.replace("\"", "")
  }

}
