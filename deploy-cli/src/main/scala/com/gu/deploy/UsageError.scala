package com.gu.deploy


class UsageError(message: String) extends Exception(message)

object UsageError {
  def apply(msg: String) = throw new UsageError(msg)
}