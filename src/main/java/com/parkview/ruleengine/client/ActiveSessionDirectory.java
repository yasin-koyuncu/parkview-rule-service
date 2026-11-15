package com.parkview.ruleengine.client;

import com.parkview.platform.domain.Place;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port: parking sessions owned by session-service. Today a read of {@code sessions}. */
public interface ActiveSessionDirectory {

    /**
     * Start of the active (not ended, not voided) session of the plate at the site of {@code place}, in its zone when it has one
     * (a spot of a zone) and anywhere at the site when it has none (a spot without a zone); empty when there is none or the place
     * names no site.
     */
    Optional<Instant> startOfActiveSession(String normalizedPlate, Place place);

    /** Id of that same active session (the one a new violation is linked to); empty when there is none. */
    Optional<UUID> idOfActiveSession(String normalizedPlate, Place place);
}
