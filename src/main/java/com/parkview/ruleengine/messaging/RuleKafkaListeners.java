package com.parkview.ruleengine.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.platform.messaging.KafkaEventDispatcher;
import com.parkview.ruleengine.dto.event.MapUpdatedEvent;
import com.parkview.ruleengine.dto.event.PaymentCompletedEvent;
import com.parkview.ruleengine.dto.event.SessionEndedEvent;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The Kafka side of the four events this service consumes, the same as {@link AmqpRuleListeners} reads from RabbitMQ (ADR 0021 of parkview-e2e). Only while
 * {@code parkview.messaging.consumer=kafka}; exactly one of the two exists, so an event is handled once.
 *
 * <p>Two listeners, because the events have two kinds of reader:
 * <ul>
 *   <li>{@link #onEvent} is the work of the service: one consumer group (the application name), so each observation, session end and fine payment is handled by one
 *       instance. One listener over the three topics (a group has one listener per topic: two would split the partitions between them).</li>
 *   <li>{@link #onMapEvent} is a cache eviction: it must happen in the memory of <b>every</b> instance, so each instance has a consumer group of its own
 *       ({@code parkviewInstanceGroupId}), as it has its own queue on RabbitMQ. It starts at the end of the topic ({@code auto.offset.reset=latest}): the cache of a
 *       new instance is empty, so the events from before it started do not matter, and a group that is new at every start would otherwise replay the whole retention.</li>
 * </ul>
 * An invalid payload is dead-lettered at once, a transient failure is retried in place with back-off and then dead-lettered (see {@code parkview-messaging}).
 */
@Component
@ConditionalOnProperty(prefix = "parkview.messaging", name = "consumer", havingValue = "kafka")
public class RuleKafkaListeners {

    static final String INSTANCE_GROUP = "#{@parkviewInstanceGroupId}";
    static final String START_AT_THE_END = "auto.offset.reset=latest";

    private final KafkaEventDispatcher work;
    private final KafkaEventDispatcher caches;

    RuleKafkaListeners(VehicleEventHandler vehicles, PaymentEventHandler payments, SessionEventHandler sessions, MapEventHandler maps,
                       ObjectMapper objectMapper, Validator validator) {
        this.work = new KafkaEventDispatcher(objectMapper, validator)
                .on(RuleEvents.VEHICLE_DETECTED, VehicleDetectedEvent.class, (id, event) -> vehicles.onVehicleDetected(event))
                .on(RuleEvents.PAYMENT_COMPLETED_FINE, PaymentCompletedEvent.class, (id, event) -> payments.onPaymentCompleted(event))
                .on(RuleEvents.SESSION_ENDED, SessionEndedEvent.class, (id, event) -> sessions.onSessionEnded(event));
        this.caches = new KafkaEventDispatcher(objectMapper, validator)
                .on(RuleEvents.MAP_UPDATED, MapUpdatedEvent.class, (id, event) -> maps.onMapUpdated(event));
    }

    @KafkaListener(topics = {RuleEvents.VEHICLE_TOPIC, RuleEvents.SESSION_TOPIC, RuleEvents.PAYMENT_TOPIC})
    void onEvent(ConsumerRecord<String, String> record) {
        work.dispatch(record);
    }

    @KafkaListener(topics = RuleEvents.MAPPING_TOPIC, groupId = INSTANCE_GROUP, properties = START_AT_THE_END)
    void onMapEvent(ConsumerRecord<String, String> record) {
        caches.dispatch(record);
    }
}
