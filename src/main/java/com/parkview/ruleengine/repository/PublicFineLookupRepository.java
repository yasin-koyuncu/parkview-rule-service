package com.parkview.ruleengine.repository;

import com.parkview.ruleengine.domain.PublicFineLookup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PublicFineLookupRepository extends JpaRepository<PublicFineLookup, Long> {

    /** The times of the wrong plates of a fine number since {@code since}, oldest first; served by {@code fine_public_lookups_fine_at_idx}. */
    @Query("select l.at from PublicFineLookup l where l.fineId = :fineId and l.outcome = 'not_found' and l.at >= :since order by l.at asc")
    List<Instant> failuresSince(UUID fineId, Instant since);

    /** Deletes up to {@code limit} rows older than {@code before}; returns how many (the job repeats while a full batch was deleted). */
    @Modifying
    @Query(value = """
            delete from fine_public_lookups where id in (
                select id from fine_public_lookups where at < :before order by at limit :limit)""", nativeQuery = true)
    int deleteOlderThan(Instant before, int limit);
}
