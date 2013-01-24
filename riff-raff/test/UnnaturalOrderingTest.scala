package test

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import utils.UnnaturalOrdering

class UnnaturalOrderingTest extends FlatSpec with ShouldMatchers {

  "UnnaturalOrdering" should "order strings in the order that is specified" in {
    val ordering = UnnaturalOrdering(List("test", "alpha", "first"))
    val result = List("first", "test").sorted(ordering)
    result should be(List("test", "first"))
  }

  it should "sort aliens to the end" in {
    val ordering = UnnaturalOrdering(List("test", "alpha", "first"))
    val result = List("bobbins", "first", "test").sorted(ordering)
    result should be(List("test", "first", "bobbins"))
  }

  it should "sort aliens using natural ordering" in {
    val ordering = UnnaturalOrdering(List("test", "alpha", "first"))
    val result = List("zebra", "bobbins", "egg", "first", "test").sorted(ordering)
    result should be(List("test", "first", "bobbins", "egg", "zebra"))
  }

  it should "sort aliens to the beginning if you feel like it" in {
    val ordering = UnnaturalOrdering(List("test", "alpha", "first"), false)
    val result = List("zebra", "bobbins", "egg", "first", "test").sorted(ordering)
    result should be(List("bobbins", "egg", "zebra", "test", "first"))

  }

}
