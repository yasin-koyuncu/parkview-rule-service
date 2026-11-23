package com.parkview.ruleengine.messaging;

/**
 * The events this service consumes, by the names both transports share: the topic is the RabbitMQ exchange of the same name (ADR 0021 of parkview-e2e), the event type is
 * the routing key and the {@code event-type} header of the Kafka record.
 */
public final class RuleEvents {

    public static final String VEHICLE_TOPIC = "parkview.vehicle";
    public static final String SESSION_TOPIC = "parkview.session";
    public static final String PAYMENT_TOPIC = "parkview.payment";
    public static final String MAPPING_TOPIC = "parkview.mapping";

    public static final String VEHICLE_DETECTED = "vehicle.detected";
    public static final String SESSION_ENDED = "session.ended";
    public static final String PAYMENT_COMPLETED_FINE = "payment.completed.fine";
    public static final String MAP_UPDATED = "map.updated";

    private RuleEvents() {
    }
}
