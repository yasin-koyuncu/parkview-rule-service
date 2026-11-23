package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.dto.event.VehicleDetectedEvent;
import com.parkview.ruleengine.service.violation.DetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handles {@code vehicle.detected}. The transport rejects invalid payloads to its dead letters; other failures propagate so the listener retries before
 * dead-lettering. Called by {@link AmqpRuleListeners} or {@link RuleKafkaListeners}.
 */
@Component
@RequiredArgsConstructor
public class VehicleEventHandler {

    private final DetectionService detectionService;

    public void onVehicleDetected(VehicleDetectedEvent event) {
        detectionService.process(event);
    }
}
