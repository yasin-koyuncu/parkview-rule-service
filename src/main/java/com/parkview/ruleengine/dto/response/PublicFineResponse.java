package com.parkview.ruleengine.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What the public fine page shows to a person who proved the fine number and the plate (ADR 0020): the explanation of ADR 0005 and the status.
 * Never snapshots, detection ids, the keeper, user ids, other plates or the internal ids of sites, zones and cameras.
 */
@Schema(description = "A fine as the public page shows it: plain language and status, no pictures and no internal ids")
public record PublicFineResponse(
        UUID fineId,
        Instant issuedAt,
        @Schema(description = "Amount of the fine") BigDecimal amount,
        @Schema(description = "ISO 4217 code of the amount", example = "SEK") String currency,
        @Schema(description = "unpaid, paid or voided", allowableValues = {"unpaid", "paid", "voided"}) String status,
        @Schema(description = "Street address of the zone, when known") String zoneAddress,
        @Schema(description = "Street address of the site, when known") String siteAddress,
        @Schema(description = "Spot number, when known") String spotNumber,
        @Schema(description = "Why the fine was issued, as stored (lower case)", example = "overstay") String reasonCode,
        @Schema(description = "Plain-language text of why the fine was issued") String reasonText,
        @Schema(description = "Plain-language text of the rule that applied; null for fines issued before evidence was recorded") String ruleSummary,
        @Schema(description = "When the violation was first detected; null when not recorded") Instant detectedAt,
        @Schema(description = "Minutes the driver had to correct it; null when not recorded") Integer graceMinutes,
        @Schema(description = "When the grace period ended; null when not recorded") Instant graceExpiredAt,
        @Schema(description = "Status of the latest dispute; null when there never was one",
                allowableValues = {"open", "upheld", "voided", "withdrawn"}) String disputeStatus
) {
}
