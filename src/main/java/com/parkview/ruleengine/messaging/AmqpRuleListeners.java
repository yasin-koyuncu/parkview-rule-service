package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.dto.event.MapUpdatedEvent;
import com.parkview.ruleengine.dto.event.PaymentCompletedEvent;
import com.parkview.ruleengine.dto.event.SessionEndedEvent;
import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * The RabbitMQ side of the four events this service consumes. Only while it reads from RabbitMQ ({@code parkview.messaging.consumer=amqp}, the way back); with
 * {@code kafka} the {@link RuleKafkaListeners} hand the same events to the same handlers, so an event is handled once. An invalid payload is rejected to the dead-letter
 * queue; another failure propagates so the listener retries before dead-lettering.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "parkview.messaging", name = "consumer", havingValue = "amqp")
public class AmqpRuleListeners {

    private final VehicleEventHandler vehicles;
    private final PaymentEventHandler payments;
    private final SessionEventHandler sessions;
    private final MapEventHandler maps;

    @RabbitListener(queues = RabbitConfig.VEHICLE_DETECTED_QUEUE)
    public void onVehicleDetected(@Payload @Valid VehicleDetectedEvent event) {
        vehicles.onVehicleDetected(event);
    }

    @RabbitListener(queues = RabbitConfig.PAYMENT_COMPLETED_QUEUE)
    public void onPaymentCompleted(@Payload @Valid PaymentCompletedEvent event) {
        payments.onPaymentCompleted(event);
    }

    @RabbitListener(queues = RabbitConfig.SESSION_ENDED_QUEUE)
    public void onSessionEnded(@Payload @Valid SessionEndedEvent event) {
        sessions.onSessionEnded(event);
    }

    @RabbitListener(queues = "#{mapUpdatedInstanceQueue.name}")
    public void onMapUpdated(@Payload @Valid MapUpdatedEvent event) {
        maps.onMapUpdated(event);
    }
}
