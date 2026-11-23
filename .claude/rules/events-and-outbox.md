---
description: Use when adding or changing a published or consumed event, a routing key, a queue or binding, a listener, or an event record in dto/event.
paths:
  - "src/main/java/com/parkview/ruleengine/messaging/**"
  - "src/main/java/com/parkview/ruleengine/config/RabbitConfig.java"
  - "src/main/java/com/parkview/ruleengine/dto/event/**"
  - "src/main/java/com/parkview/ruleengine/service/fine/FineEvents.java"
  - "src/main/java/com/parkview/ruleengine/service/violation/ViolationEvents.java"
  - "src/test/java/com/parkview/ruleengine/messaging/**"
  - "src/test/java/com/parkview/ruleengine/config/MapQueueTopologyTest.java"
  - "src/test/java/com/parkview/ruleengine/service/fine/FineEventPayloadTest.java"
last-verified: 2026-11-22
---

# Events and outbox

## Publishing

* The transport is not this service's business: `parkview.outbox.transport` (`amqp` | `kafka` | `both`, ADR 0021) picks the sink of the relay. An event added here reaches RabbitMQ and Kafka by itself (topic = the exchange, key = the aggregate id, headers `event-type` and `message-id`, the same body); there is no per-event Kafka code to write.
* `ViolationEvents` and `FineEvents` write each event to `outbox_events` with `OutboxService` (it refuses to run without a transaction). The relay, cleanup and `parkview.outbox.*` come from `parkview-outbox`.
* The published events and their exchange are listed in CLAUDE.md §1. The message id is the outbox row id.
* Payloads are camelCase JSON with `occurredAt` (the time of the fact; `timestamp` in ms where the catalogue says so). Publishers only add fields; a breaking change needs a new routing key. Events carry `siteId?` next to `zoneId?` (ADR 0009). `fine.disputed` and `fine.dispute.decided` never carry the message or the note.
* A changed payload changes three things together: the record in `dto/event`, `../parkview-e2e/contracts/events.json`, and section 4.1 of `platform-standards.md`. `FineEventPayloadTest` pins the fine payloads; the e2e `test_contracts.py` validates what is really published.

## Consuming

* The four `*EventHandler` classes hold the logic and know no transport. `AmqpRuleListeners` (default) and `RuleKafkaListeners` (`parkview.messaging.consumer=kafka`) call them; each exists only for its transport, and `RabbitConfig.ConsumerTopology`, `DeadLetterConfig` and `ListenerRetryConfig` too, so a service on Kafka leaves no queue bound. Names of events and topics shared by both: `RuleEvents`.
* RabbitMQ: queues are durable, dead-lettered to `parkview.dlx` (`DeadLetterConfig.durableQueue`): `vehicle.detected`, `payment.completed.rule-engine`, `session.ended.rule-engine`. Names are kept from earlier releases.
* `map.updated` must reach every instance: RabbitMQ gives each its own queue (`mapUpdatedInstanceQueue`: non-durable, exclusive, auto-delete; `MapQueueTopologyTest`, `MapUpdatedBrokerTest`); Kafka gives each its own consumer group (`parkviewInstanceGroupId`) that starts at the end of the topic (`RuleKafkaListenersTest`). A shared group would hand the event to one instance only. The other three events are work: one group, one listener over their topics.
* Listeners take typed records. Do not catch-all-and-log: a transient failure propagates so the retry (6 attempts: `RABBIT_RETRY_*` with `ListenerRetryConfig`, or the in-place back-off of `parkview-messaging`) and the dead letters (`parkview.dlq`, `<topic>.rule-service.dlt`) can work; handle only expected business outcomes quietly.
* The unique indexes `spot_violations_open_uq` and `spot_violations_fine_uq` guard redelivery (CLAUDE.md §3). No listener here reads `messageId`; do not rely on it.
* An observation older than `observation-max-age-seconds` is dropped; a fine is issued only for a violation seen within `fine-freshness-seconds`. Use the event time and the injected `Clock`.
* A payment the fine cannot account for (voided, already paid, other amount, unknown) becomes a `payment_needs_review` row in `fine_actions` (one per payment id) and counts `ruleengine.payments.needs_review{cause}`; never drop it.

## When it does NOT apply

REST controllers and the rule evaluation itself. Table changes: `baselines-and-schema.md`.
