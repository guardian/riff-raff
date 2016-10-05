package magenta.metrics

import com.gu.management.TimingMetric

object MagentaMetrics {
  val all = Seq(MessageBrokerMessages)

  object MessageBrokerMessages
      extends TimingMetric(
        "messages",
        "message_broker_messages",
        "Message Broker messages",
        "messages dispatched by the internal MessageBroker"
      )
}
