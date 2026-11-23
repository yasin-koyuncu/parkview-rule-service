package com.parkview.ruleengine.messaging;

import com.parkview.platform.domain.Place;
import com.parkview.ruleengine.dto.event.MapUpdatedEvent;
import com.parkview.ruleengine.service.cache.RuleCacheService;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.service.violation.ViolationResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code map.updated} from parking-mapping-service and evicts the spots and rules of that site from the caches, so a
 * re-scan, a rollback, "empty the area" or an edited rule or tariff takes effect for the next observation instead of after the
 * cache TTL, and then closes the active violations of spots that no longer exist (there is no foreign key to the spots of
 * parking-mapping-service that would do it, ADR 0010). The event is per site (ADR 0009); one that names only a zone evicts the zone's site.
 * Every instance gets every event (RabbitMQ: its own queue, see {@code RabbitConfig.ConsumerTopology#mapUpdatedInstanceQueue}; Kafka: its own consumer group,
 * see {@link RuleKafkaListeners}), so every instance evicts its own cache. Called by {@link AmqpRuleListeners} or {@link RuleKafkaListeners}.
 */
@Component
@RequiredArgsConstructor
public class MapEventHandler {

    private final SpotCacheService spotCache;
    private final RuleCacheService ruleCache;
    private final ViolationResolver violations;

    public void onMapUpdated(MapUpdatedEvent event) {
        Place place = ruleCache.resolve(event.place());
        if (place.hasSite()) {
            spotCache.evict(place.siteId());
        }
        ruleCache.evict(place);
        if (place.hasSite()) {
            violations.resolveOfRemovedSpots(place.siteId());
        }
    }
}
