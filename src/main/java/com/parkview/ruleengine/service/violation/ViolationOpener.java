package com.parkview.ruleengine.service.violation;

import com.parkview.platform.security.Plates;
import com.parkview.ruleengine.client.ActiveSessionDirectory;
import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.repository.ViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Opens violations and keeps them alive while the vehicle is re-confirmed. Resolving is in {@link ViolationResolver}, reading in
 * {@link ViolationQueryService}, fining in {@code FineIssuingService} and the scheduled jobs in {@link ViolationJobs}.
 *
 * <p>Every state change runs in its own short transaction (a failing row never blocks the others) and its event is written to the
 * outbox in that same transaction ({@link ViolationEvents}).
 *
 * <p>A violation belongs to the site of its spot and, when the spot has one, to its zone (ADR 0009): a spot without a zone is
 * controlled like one in a zone, with the rules of its site.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationOpener {

    private final ViolationRepository violations;
    private final PlateOwnerDirectory plateOwners;
    private final ActiveSessionDirectory sessions;
    private final ViolationEvents events;
    private final FineHistory fineHistory;
    private final RuleEngineProperties properties;
    private final EngineMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    /**
     * The vehicle was just seen breaking {@code type} in {@code spot}: opens a violation, or confirms
     * the existing one. A concurrent insert that loses against the partial unique index is "already exists".
     */
    public void confirm(ParkingSpot spot, ViolationType type, String plate, String cameraId) {
        confirm(spot, type, plate, cameraId, null, clock.instant());
    }

    /**
     * Same, remembering the plain-language text of the rule that decided it when a new violation is opened (it ends up in
     * the evidence of the fine).
     *
     * @param observedAt when the vehicle was seen (the event time, never later than now): it is the start of the grace period
     *                   and the {@code last_seen_at} of the violation, so a backlog never gives an old sighting a fresh grace
     */
    public void confirm(ParkingSpot spot, ViolationType type, String plate, String cameraId, String ruleSummary,
                        Instant observedAt) {
        try {
            tx.executeWithoutResult(status -> confirmOrOpen(spot, type, plate, cameraId, ruleSummary, observedAt));
        } catch (DataIntegrityViolationException e) {
            if (violations.findActive(plate, spot.id(), type).isEmpty()) {
                throw e;
            }
            log.debug("Violation {} for spot {} was opened concurrently", type, spot.id());
        }
    }

    private void confirmOrOpen(ParkingSpot spot, ViolationType type, String plate, String cameraId, String ruleSummary,
                               Instant observedAt) {
        var active = violations.findActive(plate, spot.id(), type);
        if (active.isPresent()) {
            touchIfDue(active.get(), observedAt);
            return;
        }
        // A vehicle that was already fined and is still there must not collect a second fine.
        List<UUID> fined = violations.findRecentlyFinedIds(
                plate, spot.id(), type, observedAt.minus(properties.staleAfter()), PageRequest.of(0, 1));
        if (!fined.isEmpty()) {
            violations.touch(fined.get(0), observedAt);
            return;
        }
        // The same stay must not be fined twice when the spot was replaced meanwhile (re-scan, "empty the area", rollback): the
        // new spot has a new id and the violation of the old one is closed (see ViolationResolver#resolveOfRemovedSpots), so only the fine itself remembers it. A voided
        // fine counts too: an operator decided about this stay already.
        if (properties.refineBlockHours() > 0
                && fineHistory.finedRecently(spot.place(), type, plate, observedAt.minus(properties.refineBlock()))) {
            log.debug("A fine for {} at site {} zone {} was issued within {} h: no new violation", type, spot.siteId(), spot.zoneId(),
                    properties.refineBlockHours());
            return;
        }
        open(spot, type, plate, cameraId, ruleSummary, observedAt);
    }

    private void touchIfDue(Violation violation, Instant now) {
        Duration sinceLastWrite = Duration.between(violation.getLastSeenAt(), now);
        if (sinceLastWrite.compareTo(properties.touchInterval()) >= 0) {
            violations.touch(violation.getId(), now);
        }
    }

    private void open(ParkingSpot spot, ViolationType type, String plate, String cameraId, String ruleSummary, Instant now) {
        UUID owner = plateOwners.ownerOf(plate).orElse(null);
        // the session active at the place now: session-service links the violation to it by this id and nothing else
        UUID sessionId = sessions.idOfActiveSession(plate, spot.place()).orElse(null);
        int graceMinutes = properties.graceMinutesFor(spot, type);
        Violation violation = violations.saveAndFlush(Violation.open(spot.id(), spot.siteId(), spot.zoneId(), plate, owner, type, now,
                now.plus(Duration.ofMinutes(graceMinutes)), ruleSummary, sessionId));

        events.created(violation, spot, cameraId, graceMinutes, now);
        metrics.violationCreated(type);
        log.info("Violation {} opened: {} for plate {} at site {} zone {}, grace {} min",
                violation.getId(), type, Plates.mask(plate), spot.siteId(), spot.zoneId(), graceMinutes);
    }
}
