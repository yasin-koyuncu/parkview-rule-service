package com.parkview.ruleengine.service.violation;

import com.parkview.platform.domain.Place;
import com.parkview.platform.web.ConflictException;
import com.parkview.platform.web.ResourceNotFoundException;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RuleEngineProperties;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.repository.ViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resolves violations: when the vehicle moved or left, when nobody confirms a violation any more, when its spot is gone and when
 * an operator closes it. Opening is in {@link ViolationOpener}.
 *
 * <p>Every resolution runs in its own short transaction (a failing row never blocks the others): the atomic update decides, and the
 * {@code violation.resolved} event is written to the outbox in that same transaction ({@link ViolationEvents}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationResolver {

    private static final int STALE_BATCH = 200;

    private final ViolationRepository violations;
    private final ZoneDirectory zones;
    private final ViolationEvents events;
    private final RuleEngineProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    /**
     * The plate was just observed at {@code place} (the whole site when it names no zone); every open violation of it there other
     * than {@code keepSpotId}/{@code keepType} no longer describes where it is and is resolved.
     * Pass null for both to resolve all of them.
     */
    public void resolveOthers(String plate, Place place, UUID keepSpotId, ViolationType keepType, ResolutionReason reason) {
        for (Violation open : activeOf(plate, place)) {
            boolean keep = open.getSpotId().equals(keepSpotId) && open.getViolationType() == keepType;
            if (!keep) {
                resolve(open, reason);
            }
        }
    }

    /** Resolves open violations nobody has confirmed for {@code stale-minutes}. */
    public int resolveStale() {
        Instant now = clock.instant();
        List<Violation> stale = violations.findStale(now.minus(properties.staleAfter()), PageRequest.of(0, STALE_BATCH));
        stale.forEach(v -> resolve(v, ResolutionReason.LEFT));
        return stale.size();
    }

    /**
     * Closes the active violations of the site whose spot no longer exists (as LEFT: nobody can be breaking a rule in a spot that is
     * gone, and none of them is fined). Called for {@code map.updated}, after the spot cache of the site was evicted: a re-scan,
     * a rollback, "empty the area" or deleting a spot or a zone replaces the spots, and rule-service has no foreign key that would
     * take their violations along (ADR 0010). Reads the spots through the published view of parking-mapping-service. A violation
     * that already has a fine stays as it is; one opened from a stale cache in the instant before the eviction is closed by the
     * stale sweep instead, since no detection can confirm it any more.
     *
     * @return how many violations this call resolved
     */
    public int resolveOfRemovedSpots(String siteId) {
        int resolved = 0;
        for (Violation orphan : violations.findActiveOfRemovedSpots(siteId)) {
            if (resolve(orphan, ResolutionReason.LEFT)) {
                resolved++;
            }
        }
        if (resolved > 0) {
            log.info("Closed {} violations of spots that no longer exist at site {}", resolved, siteId);
        }
        return resolved;
    }

    /** The open violations of the plate at the site of the place, only those in its zone when it names one. */
    private List<Violation> activeOf(String plate, Place place) {
        return place.hasZone()
                ? violations.findActiveByPlateAndSiteAndZone(plate, place.siteId(), place.zoneId())
                : violations.findActiveByPlateAndSite(plate, place.siteId());
    }

    /**
     * The session of the plate at the place (in its zone, when it had one) ended (session-service): the vehicle has left, so its
     * open violations there are resolved as LEFT, and none of them is fined. Idempotent: a redelivered event finds nothing open. A violation that already
     * has a fine stays as it is.
     *
     * <p>A violation that a detection confirmed after {@code endedAt} is left alone: the vehicle is still there (the driver
     * ended the session without leaving, or the event arrived late). Should a clock difference between the services make
     * this skip a violation that should have been resolved, the fine sweep still refuses it as not recently seen.
     *
     * @param place   where the session was; when it names only the zone, the zone's site is used, and a place that names neither resolves nothing
     * @param endedAt when the session ended, null when unknown (then every open violation of the plate there is resolved)
     * @return how many violations this call resolved
     */
    public int resolveEnded(String plate, Place place, Instant endedAt) {
        Place target = place.hasSite() || !place.hasZone() ? place
                : Place.of(zones.siteOf(place.zoneId()).orElse(null), place.zoneId());
        if (!target.hasSite()) {
            return 0;
        }
        int resolved = 0;
        for (Violation open : activeOf(plate, target)) {
            if (endedAt != null && open.getLastSeenAt().isAfter(endedAt)) {
                continue;
            }
            if (resolve(open, ResolutionReason.LEFT)) {
                resolved++;
            }
        }
        return resolved;
    }

    /**
     * Operator action. Idempotent for an already resolved violation. Not one transaction around the whole method: the
     * atomic update decides, and when it changes nothing the violation is read again, so that a fine issued in between is
     * reported as the conflict it is instead of as a success.
     *
     * @throws ResourceNotFoundException when the id is unknown
     * @throws ConflictException         when a fine was already issued
     */
    public void resolveManually(UUID id) {
        Violation violation = load(id);
        if (violation.getFineIssuedAt() != null) {
            throw ConflictException.coded("violation.fineAlreadyIssued", "A fine has already been issued for this violation");
        }
        if (!resolve(violation, ResolutionReason.MANUAL) && load(id).getFineIssuedAt() != null) {
            throw ConflictException.coded("violation.fineAlreadyIssued", "A fine has already been issued for this violation");
        }
    }

    private Violation load(UUID id) {
        return violations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Violation", id));
    }

    /** @return true when this call closed the violation, false when it was no longer open (resolved, fined or gone) */
    private boolean resolve(Violation violation, ResolutionReason reason) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            if (violations.resolveIfActive(violation.getId(), reason, now) == 1) {
                events.resolved(violation, reason, now);
                log.info("Violation {} resolved ({})", violation.getId(), reason);
                return true;
            }
            return false;
        }));
    }
}
