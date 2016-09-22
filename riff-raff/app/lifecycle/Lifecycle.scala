package lifecycle

/**
 * Any objects with this trait mixed in will automatically get
 * instantiated and lifecycled.  init() called by the Global
 * onStart() and shutdown called by onStop().
 */
trait Lifecycle {
  def init()
  def shutdown()
}
