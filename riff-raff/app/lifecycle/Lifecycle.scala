package lifecycle

trait Lifecycle {
  def init(): Unit
  def shutdown(): Unit
}
