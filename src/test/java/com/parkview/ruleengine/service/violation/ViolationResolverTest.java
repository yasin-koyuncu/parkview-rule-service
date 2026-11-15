package com.parkview.ruleengine.service.violation;

import com.parkview.platform.domain.Place;
import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ResolutionReason;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.dto.event.ViolationResolvedEvent;
import com.parkview.ruleengine.repository.ViolationRepository;
import com.parkview.ruleengine.service.TestFixtures;
import com.parkview.ruleengine.service.TestTransactions;
import com.parkview.platform.outbox.OutboxService;
import com.parkview.platform.web.ConflictException;
import com.parkview.platform.web.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.parkview.ruleengine.service.TestFixtures.PLATE;
import static com.parkview.ruleengine.service.TestFixtures.ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ViolationResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final ViolationRepository violations = mock(ViolationRepository.class);
    private final ZoneDirectory zones = mock(ZoneDirectory.class);
    private final SiteDirectory sites = mock(SiteDirectory.class);
    private final OutboxService events = mock(OutboxService.class);
    private ViolationResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ViolationResolver(violations, zones, new ViolationEvents(events, zones, sites), TestFixtures.properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), TestTransactions.template());
    }

    @Test
    void resolveOthersResolvesEverythingExceptTheKeptViolation() {
        UUID keepSpot = UUID.randomUUID();
        Violation keep = violationAt(keepSpot, ViolationType.WRONG_PERMIT);
        Violation otherType = violationAt(keepSpot, ViolationType.BOUNDARY_EXCEEDED);
        Violation otherSpot = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(keep, otherType, otherSpot));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        resolver.resolveOthers(PLATE, Place.of(ZONE, ZONE), keepSpot, ViolationType.WRONG_PERMIT, ResolutionReason.CORRECTED);

        verify(violations, never()).resolveIfActive(eq(keep.getId()), any(), any());
        verify(violations).resolveIfActive(otherType.getId(), ResolutionReason.CORRECTED, NOW);
        verify(violations).resolveIfActive(otherSpot.getId(), ResolutionReason.CORRECTED, NOW);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).enqueue(eq(ViolationEvents.AGGREGATE_VIOLATION), any(UUID.class),
                eq(RabbitConfig.VIOLATION_EXCHANGE), eq("violation.resolved"), payload.capture());
        ViolationResolvedEvent event = (ViolationResolvedEvent) payload.getAllValues().get(0);
        assertThat(event.reason()).isEqualTo(ResolutionReason.CORRECTED);
        assertThat(event.plate()).isEqualTo(PLATE);
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void aTypeChangeOnTheSameSpotResolvesTheOldViolationAsCorrected() {
        UUID spotId = UUID.randomUUID();
        Violation boundary = violationAt(spotId, ViolationType.BOUNDARY_EXCEEDED);
        Violation overstay = violationAt(spotId, ViolationType.OVERSTAY);
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(boundary, overstay));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        resolver.resolveOthers(PLATE, Place.of(ZONE, ZONE), spotId, ViolationType.OVERSTAY, ResolutionReason.CORRECTED);

        verify(violations).resolveIfActive(boundary.getId(), ResolutionReason.CORRECTED, NOW);
        verify(violations, never()).resolveIfActive(eq(overstay.getId()), any(), any());
    }

    @Test
    void resolveOthersWithoutKeepResolvesAll() {
        Violation a = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(a));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        resolver.resolveOthers(PLATE, Place.of(ZONE, ZONE), null, null, ResolutionReason.LEFT);

        verify(violations).resolveIfActive(a.getId(), ResolutionReason.LEFT, NOW);
    }

    @Test
    void noEventWhenSomeoneElseResolvedItFirst() {
        Violation a = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(a));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(0);

        resolver.resolveOthers(PLATE, Place.of(ZONE, ZONE), null, null, ResolutionReason.LEFT);

        verifyNoInteractions(events);
    }

    @Test
    void staleViolationsAreResolvedAsLeft() {
        Violation stale = violationAt(UUID.randomUUID(), ViolationType.BOUNDARY_EXCEEDED);
        when(violations.findStale(eq(NOW.minus(Duration.ofMinutes(15))), any(Pageable.class))).thenReturn(List.of(stale));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        assertThat(resolver.resolveStale()).isEqualTo(1);

        verify(violations).resolveIfActive(stale.getId(), ResolutionReason.LEFT, NOW);
    }

    @Test
    void violationsOfSpotsThatNoLongerExistAreResolvedAsLeft() {
        Violation orphan = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveOfRemovedSpots(ZONE)).thenReturn(List.of(orphan));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        assertThat(resolver.resolveOfRemovedSpots(ZONE)).isEqualTo(1);

        verify(violations).resolveIfActive(orphan.getId(), ResolutionReason.LEFT, NOW);
    }

    @Test
    void manualResolveClosesAnOpenViolationAndPublishes() {
        Violation open = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findById(open.getId())).thenReturn(Optional.of(open));
        when(violations.resolveIfActive(open.getId(), ResolutionReason.MANUAL, NOW)).thenReturn(1);

        resolver.resolveManually(open.getId());

        verify(events).enqueue(eq(ViolationEvents.AGGREGATE_VIOLATION), any(UUID.class), eq(RabbitConfig.VIOLATION_EXCHANGE),
                eq("violation.resolved"), any(ViolationResolvedEvent.class));
    }

    @Test
    void manualResolveOfAnAlreadyResolvedViolationIsANoOp() {
        Violation resolved = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        ReflectionTestUtils.setField(resolved, "resolvedAt", NOW.minusSeconds(60));
        when(violations.findById(resolved.getId())).thenReturn(Optional.of(resolved));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(0);

        resolver.resolveManually(resolved.getId());

        verifyNoInteractions(events);
    }

    @Test
    void manualResolveOfAFinedViolationIsAConflict() {
        Violation fined = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        fined.markFined(UUID.randomUUID(), NOW.minusSeconds(60));
        when(violations.findById(fined.getId())).thenReturn(Optional.of(fined));

        assertThatThrownBy(() -> resolver.resolveManually(fined.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void manualResolveOfAnUnknownViolationIsNotFound() {
        UUID id = UUID.randomUUID();
        when(violations.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveManually(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSessionEndResolvesTheOpenViolationsOfThePlateInTheZoneAsLeft() {
        Violation open = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        ReflectionTestUtils.setField(open, "lastSeenAt", NOW.minusSeconds(240));
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(open));
        when(violations.resolveIfActive(open.getId(), ResolutionReason.LEFT, NOW)).thenReturn(1);

        assertThat(resolver.resolveEnded(PLATE, Place.of(ZONE, ZONE), NOW.minusSeconds(60))).isEqualTo(1);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).enqueue(eq(ViolationEvents.AGGREGATE_VIOLATION), eq(open.getId()), eq(RabbitConfig.VIOLATION_EXCHANGE),
                eq("violation.resolved"), payload.capture());
        assertThat(((ViolationResolvedEvent) payload.getValue()).reason()).isEqualTo(ResolutionReason.LEFT);
    }

    @Test
    void aRedeliveredSessionEndResolvesNothingAndPublishesNothing() {
        // the first delivery resolved it: the repository no longer lists it as active, and a race loses the atomic update
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of());
        assertThat(resolver.resolveEnded(PLATE, Place.of(ZONE, ZONE), NOW)).isZero();

        Violation raced = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        ReflectionTestUtils.setField(raced, "lastSeenAt", NOW.minusSeconds(240));
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(raced));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(0);
        assertThat(resolver.resolveEnded(PLATE, Place.of(ZONE, ZONE), NOW)).isZero();

        verifyNoInteractions(events);
    }

    @Test
    void aViolationConfirmedAfterTheSessionEndedMeansTheVehicleIsStillThereAndStaysOpen() {
        Violation stillThere = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        ReflectionTestUtils.setField(stillThere, "lastSeenAt", NOW.minusSeconds(10));
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(stillThere));

        assertThat(resolver.resolveEnded(PLATE, Place.of(ZONE, ZONE), NOW.minusSeconds(120))).isZero();

        verify(violations, never()).resolveIfActive(any(), any(), any());
    }

    @Test
    void aSessionEndWithoutATimeResolvesEveryOpenViolation() {
        Violation open = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, ZONE, ZONE)).thenReturn(List.of(open));
        when(violations.resolveIfActive(any(), any(), any())).thenReturn(1);

        assertThat(resolver.resolveEnded(PLATE, Place.of(ZONE, ZONE), null)).isEqualTo(1);
    }

    @Test
    void aFineIssuedBetweenTheCheckAndTheUpdateIsAConflictNotASuccess() {
        Violation open = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        Violation fined = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        ReflectionTestUtils.setField(fined, "id", open.getId());
        fined.markFined(UUID.randomUUID(), NOW.minusSeconds(1));
        // first read: still open; the atomic update changes nothing; second read: the sweep fined it meanwhile
        when(violations.findById(open.getId())).thenReturn(Optional.of(open)).thenReturn(Optional.of(fined));
        when(violations.resolveIfActive(open.getId(), ResolutionReason.MANUAL, NOW)).thenReturn(0);

        assertThatThrownBy(() -> resolver.resolveManually(open.getId())).isInstanceOf(ConflictException.class);

        verifyNoInteractions(events);
    }

    private static Violation violationAt(UUID spotId, ViolationType type) {
        Violation v = TestFixtures.violation(type, NOW.minusSeconds(300), NOW.plusSeconds(300));
        ReflectionTestUtils.setField(v, "spotId", spotId);
        return v;
    }

    @Test
    void aSessionEndThatNamesOnlyTheZoneUsesTheSiteOfThatZone() {
        Violation open = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(zones.siteOf("z9")).thenReturn(Optional.of("s9"));
        when(violations.findActiveByPlateAndSiteAndZone(PLATE, "s9", "z9")).thenReturn(List.of(open));
        when(violations.resolveIfActive(open.getId(), ResolutionReason.LEFT, NOW)).thenReturn(1);

        assertThat(resolver.resolveEnded(PLATE, Place.of(null, "z9"), null)).isEqualTo(1);
    }

    @Test
    void aSessionEndOfAnUnknownZoneOrOfNoPlaceResolvesNothing() {
        when(zones.siteOf("zX")).thenReturn(Optional.empty());

        assertThat(resolver.resolveEnded(PLATE, Place.of(null, "zX"), NOW)).isZero();
        assertThat(resolver.resolveEnded(PLATE, Place.of(null, null), NOW)).isZero();

        verify(violations, never()).findActiveByPlateAndSite(any(), any());
        verify(violations, never()).findActiveByPlateAndSiteAndZone(any(), any(), any());
    }

    @Test
    void aSessionEndAtASiteWithoutAZoneLooksAtTheWholeSite() {
        Violation open = violationAt(UUID.randomUUID(), ViolationType.WRONG_PERMIT);
        when(violations.findActiveByPlateAndSite(PLATE, "s1")).thenReturn(List.of(open));
        when(violations.resolveIfActive(open.getId(), ResolutionReason.LEFT, NOW)).thenReturn(1);

        assertThat(resolver.resolveEnded(PLATE, Place.site("s1"), null)).isEqualTo(1);
    }
}
