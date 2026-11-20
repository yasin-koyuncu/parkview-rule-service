package com.parkview.ruleengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One look-up on the public fine page (ADR 0020). It holds the fine number that was asked for (whether or not it exists), the outcome and a
 * keyed hash of the caller's address; never the plate that was typed. The wrong plates of one fine number within the window are what locks it.
 */
@Entity
@Table(name = "fine_public_lookups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicFineLookup {

    public static final String OK = "ok";
    public static final String NOT_FOUND = "not_found";
    public static final String LOCKED = "locked";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fine_id", nullable = false, updatable = false)
    private UUID fineId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Column(nullable = false, updatable = false)
    private String outcome;

    @Column(name = "client_hash", updatable = false)
    private String clientHash;

    public static PublicFineLookup of(UUID fineId, Instant at, String outcome, String clientHash) {
        PublicFineLookup row = new PublicFineLookup();
        row.fineId = fineId;
        row.at = at;
        row.outcome = outcome;
        row.clientHash = clientHash;
        return row;
    }
}
