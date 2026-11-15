package com.parkview.ruleengine.service.violation;

import com.parkview.platform.domain.Place;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.repository.FineRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FineHistoryTest {

    private static final Instant SINCE = Instant.parse("2026-10-09T04:00:00Z");

    private final FineRepository fines = mock(FineRepository.class);
    private final FineHistory history = new FineHistory(fines);

    @Test
    void aPlaceInAZoneLooksAtTheFinesOfThatZone() {
        when(fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual("ABC123", "z1", ViolationType.OVERSTAY, SINCE)).thenReturn(true);

        assertThat(history.finedRecently(Place.of("s1", "z1"), ViolationType.OVERSTAY, "ABC123", SINCE)).isTrue();

        verify(fines, never()).existsByPlateAndSiteIdAndZoneIdIsNullAndReasonAndIssuedAtGreaterThanEqual(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aPlaceWithoutAZoneLooksAtTheFinesOfTheSiteOutsideTheZones() {
        when(fines.existsByPlateAndSiteIdAndZoneIdIsNullAndReasonAndIssuedAtGreaterThanEqual("ABC123", "s1", ViolationType.OVERSTAY, SINCE))
                .thenReturn(true);

        assertThat(history.finedRecently(Place.site("s1"), ViolationType.OVERSTAY, "ABC123", SINCE)).isTrue();
        assertThat(history.finedRecently(Place.site("s2"), ViolationType.OVERSTAY, "ABC123", SINCE)).isFalse();
    }
}
