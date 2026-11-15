package com.parkview.ruleengine.service.violation;

import com.parkview.ruleengine.client.ActiveSessionDirectory;
import com.parkview.ruleengine.client.PlateOwnerDirectory;
import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.client.ZoneDirectory;
import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.domain.value.ParkingSpot;
import com.parkview.ruleengine.dto.event.ViolationCreatedEvent;
import com.parkview.ruleengine.engine.EngineMetrics;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.repository.ViolationRepository;
import com.parkview.ruleengine.service.TestFixtures;
import com.parkview.ruleengine.service.TestTransactions;
import com.parkview.platform.outbox.OutboxService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.groups.Tuple.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ViolationOpenerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final ViolationRepository violations = mock(ViolationRepository.class);
    private final FineRepository fines = mock(FineRepository.class);
    private final PlateOwnerDirectory owners = mock(PlateOwnerDirectory.class);
    private final ZoneDirectory zones = mock(ZoneDirectory.class);
    private final SiteDirectory sites = mock(SiteDirectory.class);
    private final ActiveSessionDirectory sessions = mock(ActiveSessionDirectory.class);
    private final OutboxService events = mock(OutboxService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ViolationOpener opener;

    @BeforeEach
    void setUp() {
        opener = new ViolationOpener(violations, owners, sessions, new ViolationEvents(events, zones, sites), new FineHistory(fines),
                TestFixtures.properties(), new EngineMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC), TestTransactions.template());
        when(owners.ownerOf(anyString())).thenReturn(Optional.empty());
        when(zones.addressOf(anyString())).thenReturn(Optional.empty());
        when(violations.saveAndFlush(any(Violation.class))).thenAnswer(inv -> {
            Violation v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
            return v;
        });
        when(violations.findRecentlyFinedIds(anyString(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of());
    }

    @Test
    void opensAViolationWithTheSpotsGraceAndPublishesCreated() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 45);
        UUID owner = UUID.randomUUID();
        when(owners.ownerOf(PLATE)).thenReturn(Optional.of(owner));
        when(zones.addressOf(ZONE)).thenReturn(Optional.of("Storgatan 1"));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        ArgumentCaptor<Violation> saved = ArgumentCaptor.forClass(Violation.class);
        verify(violations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getGraceUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(45)));
        assertThat(saved.getValue().getLastSeenAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getUserId()).isEqualTo(owner);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).enqueue(eq(ViolationEvents.AGGREGATE_VIOLATION), any(UUID.class), eq(RabbitConfig.VIOLATION_EXCHANGE),
                eq("violation.created"), payload.capture());
        ViolationCreatedEvent event = (ViolationCreatedEvent) payload.getValue();
        assertThat(event.plate()).isEqualTo(PLATE);
        assertThat(event.zoneId()).isEqualTo(ZONE);
        assertThat(event.zoneAddress()).isEqualTo("Storgatan 1");
        assertThat(event.cameraId()).isEqualTo("cam-1");
        assertThat(event.userId()).isEqualTo(owner);
        assertThat(event.spotId()).isEqualTo(spot.id());
        assertThat(event.spotNumber()).isEqualTo("A1");
        assertThat(event.violationType()).isEqualTo(ViolationType.WRONG_PERMIT);
        assertThat(event.graceMinutes()).isEqualTo(45);
        assertThat(event.timestamp()).isEqualTo(NOW.toEpochMilli());
        assertThat(event.sessionId()).isNull();
        assertThat(saved.getValue().getSessionId()).isNull();
    }

    @Test
    void aViolationOpenedWhileASessionIsActiveCarriesItsIdOnTheRowAndTheEvent() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 45);
        UUID sessionId = UUID.randomUUID();
        when(sessions.idOfActiveSession(PLATE, spot.place())).thenReturn(Optional.of(sessionId));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        ArgumentCaptor<Violation> saved = ArgumentCaptor.forClass(Violation.class);
        verify(violations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo(sessionId);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).enqueue(eq(ViolationEvents.AGGREGATE_VIOLATION), any(UUID.class), eq(RabbitConfig.VIOLATION_EXCHANGE),
                eq("violation.created"), payload.capture());
        assertThat(((ViolationCreatedEvent) payload.getValue()).sessionId()).isEqualTo(sessionId);
    }

    @Test
    void graceFallsBackToTheConfiguredDefaultsPerType() {
        ParkingSpot spot = TestFixtures.spot("paid", null, null, null);
        when(violations.findActive(anyString(), any(), any())).thenReturn(Optional.empty());

        for (ViolationType type : ViolationType.values()) {
            opener.confirm(spot, type, PLATE, "cam-1");
        }

        ArgumentCaptor<Violation> saved = ArgumentCaptor.forClass(Violation.class);
        verify(violations, org.mockito.Mockito.times(ViolationType.values().length)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Violation::getViolationType, v -> Duration.between(NOW, v.getGraceUntil()).toMinutes())
                .containsExactlyInAnyOrder(
                        tuple(ViolationType.BOUNDARY_EXCEEDED, 10L),
                        tuple(ViolationType.WRONG_PERMIT, 30L),
                        tuple(ViolationType.OVERSTAY, 5L),
                        tuple(ViolationType.NO_PARKING, 5L),
                        tuple(ViolationType.WRONG_VEHICLE_TYPE, 10L));
    }

    @Test
    void theSpotsOwnGraceOnlyOverridesPermitAndBoundaryViolations() {
        ParkingSpot spot = TestFixtures.spot("paid", null, 7, 45);
        when(violations.findActive(anyString(), any(), any())).thenReturn(Optional.empty());

        opener.confirm(spot, ViolationType.BOUNDARY_EXCEEDED, PLATE, "cam-1");
        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");
        opener.confirm(spot, ViolationType.NO_PARKING, PLATE, "cam-1");

        ArgumentCaptor<Violation> saved = ArgumentCaptor.forClass(Violation.class);
        verify(violations, org.mockito.Mockito.times(3)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(v -> Duration.between(NOW, v.getGraceUntil()).toMinutes())
                .containsExactly(7L, 45L, 5L);
    }

    @Test
    void everyNewViolationIsCountedByType() {
        ParkingSpot spot = TestFixtures.spot("ev", null, 0, 0);
        when(violations.findActive(anyString(), any(), any())).thenReturn(Optional.empty());

        opener.confirm(spot, ViolationType.WRONG_VEHICLE_TYPE, PLATE, "cam-1");

        assertThat(registry.counter("ruleengine.violations.created", "type", "WRONG_VEHICLE_TYPE").count()).isEqualTo(1.0);
        assertThat(registry.counter("ruleengine.violations.created", "type", "OVERSTAY").count()).isZero();
    }

    @Test
    void aReConfirmedViolationIsNotCountedAgain() {
        ParkingSpot spot = TestFixtures.spot("paid", null, 0, 0);
        Violation old = TestFixtures.violation(ViolationType.OVERSTAY, NOW.minusSeconds(120), NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.OVERSTAY)).thenReturn(Optional.of(old));

        opener.confirm(spot, ViolationType.OVERSTAY, PLATE, "cam-1");

        assertThat(registry.counter("ruleengine.violations.created", "type", "OVERSTAY").count()).isZero();
        verify(violations).touch(old.getId(), NOW);
    }

    @Test
    void anExistingActiveViolationIsOnlyTouchedWhenTheLastWriteIsOldEnough() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        Violation fresh = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(5), NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.of(fresh));

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verify(violations, never()).touch(any(), any());
        verify(violations, never()).saveAndFlush(any());

        Violation old = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(120), NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.of(old));

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verify(violations).touch(old.getId(), NOW);
        verify(violations, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void aVehicleThatWasAlreadyFinedAndIsStillThereDoesNotGetASecondViolation() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        UUID finedId = UUID.randomUUID();
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());
        when(violations.findRecentlyFinedIds(eq(PLATE), eq(spot.id()), eq(ViolationType.WRONG_PERMIT),
                eq(NOW.minus(Duration.ofMinutes(15))), any(Pageable.class))).thenReturn(List.of(finedId));

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verify(violations).touch(finedId, NOW);
        verify(violations, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void aLostInsertRaceIsTreatedAsAlreadyExisting() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        Violation winner = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW, NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(violations.saveAndFlush(any(Violation.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1");

        verifyNoInteractions(events);
    }

    @Test
    void anIntegrityErrorThatIsNotADuplicateIsNotSwallowed() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", 0, 0);
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());
        when(violations.saveAndFlush(any(Violation.class))).thenThrow(new DataIntegrityViolationException("fk violation"));

        assertThatThrownBy(() -> opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOldObservationStartsItsGraceAtThePointItWasMadeNotAtProcessingTime() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", null, 45);
        Instant seen = NOW.minus(Duration.ofMinutes(8));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, seen);

        ArgumentCaptor<Violation> saved = ArgumentCaptor.forClass(Violation.class);
        verify(violations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDetectedAt()).isEqualTo(seen);
        assertThat(saved.getValue().getLastSeenAt()).isEqualTo(seen);
        assertThat(saved.getValue().getGraceUntil()).isEqualTo(seen.plus(Duration.ofMinutes(45)));
    }

    @Test
    void aReConfirmationWithAnOldTimeNeverMovesLastSeenForwardToNow() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", null, null);
        Violation active = TestFixtures.violation(ViolationType.WRONG_PERMIT, NOW.minusSeconds(900), NOW.plusSeconds(600));
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.of(active));
        Instant seen = NOW.minus(Duration.ofMinutes(5));

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, seen);

        verify(violations).touch(active.getId(), seen);
        verify(violations, never()).touch(active.getId(), NOW);
    }

    @Test
    void aFineOfTheSamePlateZoneAndReasonWithinTheBlockWindowBlocksANewViolationWhateverTheSpot() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", null, null);
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());
        when(fines.existsByPlateAndZoneIdAndReasonAndIssuedAtGreaterThanEqual(PLATE, ZONE, ViolationType.WRONG_PERMIT,
                NOW.minus(Duration.ofHours(6)))).thenReturn(true);

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, NOW);

        verify(violations, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void withoutARecentFineANewViolationIsOpenedAsBefore() {
        ParkingSpot spot = TestFixtures.spot("permit", "resident", null, null);
        when(violations.findActive(PLATE, spot.id(), ViolationType.WRONG_PERMIT)).thenReturn(Optional.empty());

        opener.confirm(spot, ViolationType.WRONG_PERMIT, PLATE, "cam-1", null, NOW);

        verify(violations).saveAndFlush(any(Violation.class));
    }
}
