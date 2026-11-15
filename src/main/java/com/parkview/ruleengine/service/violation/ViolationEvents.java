package com.parkview.ruleengine.service.violation;

import com.parkview.platform.outbox.OutboxService;
import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.dto.event.ViolationCreatedEvent;
import com.parkview.ruleengine.dto.event.ViolationResolvedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The violation events of the catalogue ({@code violation.created}, {@code violation.resolved}): the payload of each and the routing
 * key it goes out with. They are written to the outbox in the transaction of the change ({@link OutboxService}) and published by the
 * relay; all events of one violation share the aggregate id, so they go out in the order they were written.
 */
@Component
@RequiredArgsConstructor
class ViolationEvents {

    /** Aggregate type of the events of a violation in the outbox: they are published in the order they were written. */
    static final String AGGREGATE_VIOLATION = "violation";

    private final OutboxService outbox;
    private final ZoneDirectory zones;
    private final SiteDirectory sites;

    /** {@code zoneAddress} is null for a spot without a zone; {@code siteAddress} is the address of the site (ADR 0009). */
    void created(Violation violation, ParkingSpot spot, String cameraId, int graceMinutes, Instant at) {
        String zoneAddress = spot.zoneId() == null ? null : zones.addressOf(spot.zoneId()).orElse(null);
        outbox.enqueue(AGGREGATE_VIOLATION, violation.getId(), RabbitConfig.VIOLATION_EXCHANGE,
                RabbitConfig.VIOLATION_CREATED_KEY, new ViolationCreatedEvent(violation.getId(), violation.getPlate(), spot.zoneId(),
                        zoneAddress, spot.siteId(), sites.addressOf(spot.siteId()).orElse(null), cameraId, violation.getUserId(),
                        spot.id(), spot.spotNumber(), violation.getViolationType(), graceMinutes, at.toEpochMilli(),
                        violation.getSessionId()));
    }

    void resolved(Violation violation, ResolutionReason reason, Instant at) {
        outbox.enqueue(AGGREGATE_VIOLATION, violation.getId(), RabbitConfig.VIOLATION_EXCHANGE,
                RabbitConfig.VIOLATION_RESOLVED_KEY, new ViolationResolvedEvent(violation.getId(),
                        violation.getPlate(), violation.getZoneId(), violation.getSiteId(), reason, at));
    }
}
