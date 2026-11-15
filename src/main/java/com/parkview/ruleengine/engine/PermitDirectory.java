package com.parkview.ruleengine.engine;

import com.parkview.platform.domain.Place;

import java.time.Instant;
import java.util.Collection;

/** Port of the engine: the permits a plate holds. Implemented by the permit service, so the engine does not depend on the service layer. */
public interface PermitDirectory {

    /**
     * True when the plate holds a valid permit of every given type (case-insensitive) that covers the place at {@code at}: a permit
     * for every site, for the site, or (a place in a zone) for the zone. A place without a zone is covered by permits of its site
     * only (a permit for a zone does not cover it).
     */
    boolean holdsAll(String normalizedPlate, Place place, Collection<String> permitTypes, Instant at);
}
