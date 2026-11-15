package com.parkview.ruleengine.service.violation;

import com.parkview.platform.domain.Place;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.repository.FineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** What the violation lifecycle needs to know about fines already issued, so that {@link FineRepository} stays out of the lifecycle classes. */
@Component
@RequiredArgsConstructor
class FineHistory {

    private final FineRepository fines;

    /**
     * A fine for the reason in the zone of the place, or, for a place without a zone, at its site outside the zones (a fine in a zone
     * does not count for the parts of the site outside the zones). Voided fines count: an operator decided about the stay already.
     */
    boolean finedRecently(Place place, ViolationType type, String plate, Instant since) {
        return place.hasZone()
                ? fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual(plate, place.zoneId(), type, since)
                : fines.existsByPlateAndSiteIdAndZoneIdIsNullAndReasonAndIssuedAtGreaterThanEqual(plate, place.siteId(), type, since);
    }
}
