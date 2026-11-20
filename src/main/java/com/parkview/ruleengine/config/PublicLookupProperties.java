package com.parkview.ruleengine.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The public fine page ({@code parkview.public-lookup.*}, ADR 0020): a fine is read with its number and the plate, without signing in.
 *
 * @param maxFailures how many wrong plates one fine number takes within {@code window}; the next try is answered 429 until the oldest
 *                    failure leaves the window. Counted per fine number whether or not the fine exists, so the page does not tell
 * @param window      the period the failures are counted in
 * @param keep        how long the rows of {@code fine_public_lookups} are kept (they hold no plate, only a keyed hash of the caller's address)
 * @param hashSalt    the key of the address hash; empty (development) hashes without a key. Set it in production
 * @param pruneCron   when the rows older than {@code keep} are deleted (Spring cron, server time zone)
 * @param pruneBatch  rows per delete
 */
@Validated
@ConfigurationProperties("parkview.public-lookup")
public record PublicLookupProperties(
        @DefaultValue("5") @Min(1) int maxFailures,
        @DefaultValue("PT15M") Duration window,
        @DefaultValue("P30D") Duration keep,
        @DefaultValue("") String hashSalt,
        @DefaultValue("0 35 3 * * *") String pruneCron,
        @DefaultValue("1000") @Min(1) int pruneBatch
) {

    public PublicLookupProperties {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("parkview.public-lookup.window must be a positive duration");
        }
        if (keep == null || keep.compareTo(window) < 0) {
            throw new IllegalArgumentException("parkview.public-lookup.keep must be at least as long as the window");
        }
    }
}
