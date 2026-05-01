package org.mockito.internal.handler

import org.mockito.invocation.{Invocation, MockHandler}

/** A safe replacement for mockito-scala's ScalaNullResultGuardian that avoids
  * Scala runtime reflection on method return types.
  *
  * The original implementation checks if a mock method's return type is a Scala
  * value class using scala.reflect.runtime, which triggers CyclicReference
  * errors for AWS SDK v2 types whose builder interfaces form cyclic type
  * hierarchies.
  *
  * This version simply delegates to the wrapped handler without the reflection
  * check. The trade-off is that mocks of methods returning Scala value classes
  * will return null instead of a zero-value default, but none of our tests rely
  * on that behaviour.
  */
class ScalaNullResultGuardian[T](delegate: MockHandler[T])
    extends MockHandler[T] {
  override def handle(invocation: Invocation): AnyRef =
    delegate.handle(invocation)
  override def getMockSettings = delegate.getMockSettings
  override def getInvocationContainer = delegate.getInvocationContainer
}
