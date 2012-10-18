package lifecycle

import play.Application

/**
 * Any objects with this trait mixed in will automatically get
 * instantiated and lifecycled.  init() called by the Global
 * onStart() and shutdown called by onStop().
 */
trait Lifecycle {
  def init(app: Application)
  def shutdown(app: Application)
}

trait LifecycleWithoutApp extends Lifecycle {
  def init(app:Application) {init()}
  def shutdown(app:Application) {shutdown()}
  def init()
  def shutdown()
}
