package com.parkview.ruleengine.config;

import com.parkview.ruleengine.messaging.DeadLetterConfig;
import com.parkview.ruleengine.messaging.RuleEvents;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * RabbitMQ topology owned by this service: its input queues (dead-lettered to
 * {@code parkview.dlx}) and the exchanges it publishes to or consumes from. The dead-letter
 * objects themselves come from {@link DeadLetterConfig}.
 *
 * <p>Three of the queues are durable and shared by all instances (competing consumers: each message is handled once).
 * {@code map.updated} is different: it only evicts a cache that lives in the memory of every instance, so each instance
 * declares its own exclusive, auto-delete queue and all of them receive every event (see {@link #mapUpdatedInstanceQueue}).
 */
@Configuration
public class RabbitConfig {

    public static final String VEHICLE_EXCHANGE = RuleEvents.VEHICLE_TOPIC;
    public static final String VIOLATION_EXCHANGE = "parkview.violation";
    public static final String PAYMENT_EXCHANGE = RuleEvents.PAYMENT_TOPIC;
    public static final String SESSION_EXCHANGE = RuleEvents.SESSION_TOPIC;
    public static final String MAPPING_EXCHANGE = RuleEvents.MAPPING_TOPIC;

    public static final String VEHICLE_DETECTED_QUEUE = "vehicle.detected";
    public static final String PAYMENT_COMPLETED_QUEUE = "payment.completed.rule-engine";
    public static final String SESSION_ENDED_QUEUE = "session.ended.rule-engine";
    /** The prefix of the per-instance queues of map.updated: every instance evicts its own caches. */
    public static final String MAP_UPDATED_QUEUE = "map.updated.rule-engine";

    public static final String VEHICLE_DETECTED_KEY = RuleEvents.VEHICLE_DETECTED;
    public static final String PAYMENT_COMPLETED_FINE_KEY = RuleEvents.PAYMENT_COMPLETED_FINE;
    public static final String SESSION_ENDED_KEY = RuleEvents.SESSION_ENDED;
    public static final String MAP_UPDATED_KEY = RuleEvents.MAP_UPDATED;

    public static final String VIOLATION_CREATED_KEY = "violation.created";
    public static final String VIOLATION_RESOLVED_KEY = "violation.resolved";
    public static final String FINE_ISSUED_KEY = "fine.issued";
    public static final String FINE_PAID_KEY = "fine.paid";
    public static final String FINE_VOIDED_KEY = "fine.voided";
    public static final String FINE_DISPUTED_KEY = "fine.disputed";
    public static final String FINE_DISPUTE_DECIDED_KEY = "fine.dispute.decided";

    @Bean
    TopicExchange vehicleExchange() {
        return new TopicExchange(VEHICLE_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange violationExchange() {
        return new TopicExchange(VIOLATION_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange paymentExchange() {
        return new TopicExchange(PAYMENT_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange sessionExchange() {
        return new TopicExchange(SESSION_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange mappingExchange() {
        return new TopicExchange(MAPPING_EXCHANGE, true, false);
    }

    /**
     * The queues of the events this service consumes and their bindings, only while it reads from RabbitMQ ({@code parkview.messaging.consumer=amqp}, the way back). A service
     * that reads from Kafka declares none: a queue that stays bound would collect every event unread (ADR 0021 of parkview-e2e). The exchanges above stay: this service
     * publishes to some of them.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "parkview.messaging", name = "consumer", havingValue = "amqp")
    static class ConsumerTopology {

        @Bean
        Queue vehicleDetectedQueue() {
            return DeadLetterConfig.durableQueue(VEHICLE_DETECTED_QUEUE);
        }

        @Bean
        Queue paymentCompletedQueue() {
            return DeadLetterConfig.durableQueue(PAYMENT_COMPLETED_QUEUE);
        }

        @Bean
        Queue sessionEndedQueue() {
            return DeadLetterConfig.durableQueue(SESSION_ENDED_QUEUE);
        }

        /**
         * This instance's own queue for {@code map.updated}: not durable, exclusive to the connection and deleted with it. With
         * one durable queue for all instances RabbitMQ would hand each event to one of them and the others would keep the
         * old spots of the zone until the cache TTL. Events published while an instance is down are not needed: its cache
         * starts empty. A message that is rejected is dead-lettered like those of the other queues.
         */
        @Bean
        Queue mapUpdatedInstanceQueue() {
            return QueueBuilder.nonDurable(MAP_UPDATED_QUEUE + ".instance-" + UUID.randomUUID())
                    .exclusive()
                    .autoDelete()
                    .deadLetterExchange(DeadLetterConfig.DLX)
                    .build();
        }

        @Bean
        Binding vehicleDetectedBinding(Queue vehicleDetectedQueue, TopicExchange vehicleExchange) {
            return BindingBuilder.bind(vehicleDetectedQueue).to(vehicleExchange).with(VEHICLE_DETECTED_KEY);
        }

        @Bean
        Binding paymentCompletedBinding(Queue paymentCompletedQueue, TopicExchange paymentExchange) {
            return BindingBuilder.bind(paymentCompletedQueue).to(paymentExchange).with(PAYMENT_COMPLETED_FINE_KEY);
        }

        @Bean
        Binding sessionEndedBinding(Queue sessionEndedQueue, TopicExchange sessionExchange) {
            return BindingBuilder.bind(sessionEndedQueue).to(sessionExchange).with(SESSION_ENDED_KEY);
        }

        @Bean
        Binding mapUpdatedBinding(Queue mapUpdatedInstanceQueue, TopicExchange mappingExchange) {
            return BindingBuilder.bind(mapUpdatedInstanceQueue).to(mappingExchange).with(MAP_UPDATED_KEY);
        }
    }
}
