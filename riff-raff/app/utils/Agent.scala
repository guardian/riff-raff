package utils

import java.util.concurrent.Semaphore
import java.util.function.UnaryOperator
import scala.concurrent.{ExecutionContext, Future}

/** Minimalistic re-implementation of the deperecated akka-agent package to
  * handle modification of simple state across multiple threads.
  *
  * Only functions that riff-raff require have been re-implemented. I've not
  * based this implementation on the original implementation and have opted to
  * use Java native libraries to handle locks.
  *
  * @param initialState
  *   initial state to initialize the agent with
  */
class Agent[T](initialState: T)(implicit ec: ExecutionContext) {

  // Semaphore (in this case a Binary Semaphore) handles locks on a first-in-first-out basis,
  // similar to how Actors (and previously Agents) handle incoming messages. This ensures that state is accessed fairly
  // and in order of first come first served.
  private def lock = new Semaphore(1, true)
  var state = initialState

  /** Immediately return state without waiting on locks. This is not thread
    * safe, and should only be used for reading state.
    *
    * @return
    *   the current state of the agent
    */
  def apply(): T = state

  /** Queue an operation to be done to the state and block until completed
    *
    * @param operation
    *   action to be performed on the state
    */
  def send(operation: UnaryOperator[T]): Unit = {
    lock.acquire()
    try {
      state = operation(state)
    } finally {
      lock.release()
    }
  }

  /** Queue an operation to be done asynchronously to the state
    *
    * @param operation
    *   action to be performed on the state
    */
  def sendOff(operation: UnaryOperator[T]): Unit = {
    Future {
      send(operation)
    }
  }

  /** Queue to receive the state once all operations are completed
    *
    * @return
    *   the current state of the agent
    */
  def future(): Future[T] = Future {
    lock.acquire()
    lock.release()
    state
  }
}

object Agent {
  def apply[T](initialState: T)(implicit ec: ExecutionContext) =
    new Agent[T](initialState)
}
