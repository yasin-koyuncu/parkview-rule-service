package com.parkview.ruleengine.service.fine;

import com.parkview.ruleengine.config.PublicLookupProperties;
import com.parkview.ruleengine.repository.PublicFineLookupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Deletes the rows of {@code fine_public_lookups} older than {@code parkview.public-lookup.keep} (ADR 0020), in batches of their own transaction.
 * They hold no plate, so this is housekeeping and not part of the retention job of the fines; running on several instances is harmless (a delete
 * is idempotent). The log carries a count only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicLookupPruneJob {

    private static final int MAX_BATCHES = 200;

    private final PublicFineLookupRepository lookups;
    private final PublicLookupProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Scheduled(cron = "${parkview.public-lookup.prune-cron:0 35 3 * * *}")
    void scheduledRun() {
        prune();
    }

    /** @return how many rows were deleted */
    public long prune() {
        Instant before = clock.instant().minus(properties.keep());
        long total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            Integer deleted = transaction.execute(status -> lookups.deleteOlderThan(before, properties.pruneBatch()));
            int n = deleted == null ? 0 : deleted;
            total += n;
            if (n < properties.pruneBatch()) {
                break;
            }
        }
        if (total > 0) {
            log.info("Public fine look-ups: deleted {} rows older than {}", total, properties.keep());
        }
        return total;
    }
}
