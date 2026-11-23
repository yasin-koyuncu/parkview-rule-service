package com.parkview.ruleengine.messaging;

import com.parkview.platform.domain.Place;
import com.parkview.platform.security.Plates;
import com.parkview.ruleengine.dto.event.SessionEndedEvent;
import com.parkview.ruleengine.service.violation.ViolationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code session.ended} from session-service: the vehicle has left, so its open violations at that site (in that zone,
 * when the session had one) are resolved as LEFT instead of waiting for the stale sweep (and, above all, are not fined meanwhile). Redelivery is harmless.
 * Called by {@link AmqpRuleListeners} or {@link RuleKafkaListeners}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventHandler {

    private final ViolationResolver violationResolver;

    public void onSessionEnded(SessionEndedEvent event) {
        String plate = Plates.normalizeOrNull(event.plate());
        if (plate == null) {
            return;
        }
        Place place = event.place();
        int resolved = violationResolver.resolveEnded(plate, place, event.endTime());
        if (resolved > 0) {
            log.debug("Session ended at site {} zone {}: {} violations resolved as LEFT", place.siteId(), place.zoneId(), resolved);
        }
    }
}
