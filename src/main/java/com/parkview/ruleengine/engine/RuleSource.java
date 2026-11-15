package com.parkview.ruleengine.engine;

import com.parkview.platform.domain.Place;
import com.parkview.ruleengine.domain.value.ZoneRule;

import java.util.List;

/** Port of the engine: the enabled rules that apply at a place. Implemented by the rule cache, so the engine does not depend on the service layer. */
public interface RuleSource {

    /**
     * The rules that apply at a spot: the zone rules of the zone of {@code place} (none without a zone) and the site rules of its
     * site, in no particular order.
     */
    List<ZoneRule> rulesOf(Place place);
}
