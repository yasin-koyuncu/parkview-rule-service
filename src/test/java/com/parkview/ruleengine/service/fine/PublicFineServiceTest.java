package com.parkview.ruleengine.service.fine;

import com.parkview.platform.web.ResourceNotFoundException;
import com.parkview.platform.web.TooManyRequestsException;
import com.parkview.ruleengine.config.PublicLookupProperties;
import com.parkview.ruleengine.domain.Fine;
import com.parkview.ruleengine.domain.PublicFineLookup;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.domain.type.ViolationType;
import com.parkview.ruleengine.dto.response.PublicFineResponse;
import com.parkview.ruleengine.repository.FineRepository;
import com.parkview.ruleengine.repository.PublicFineLookupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR 0020: the proof is the fine number and the plate; "no such fine" and "wrong plate" are one answer; guessing is limited per fine number. */
class PublicFineServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final UUID FINE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final FineRepository fines = mock(FineRepository.class);
    private final FineEvidenceService evidence = mock(FineEvidenceService.class);
    private final PublicFineLookupRepository lookups = mock(PublicFineLookupRepository.class);
    private final PublicLookupProperties properties = new PublicLookupProperties(5, Duration.ofMinutes(15), Duration.ofDays(30), "", "0 35 3 * * *", 1000);
    private final PublicFineService service = new PublicFineService(fines, evidence, lookups, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private Fine fine;

    @BeforeEach
    void setUp() {
        Violation violation = Violation.open(UUID.randomUUID(), "z1", "z1", "ABC123", null, ViolationType.OVERSTAY, NOW.minusSeconds(3600),
                NOW.minusSeconds(3300), "The stay limit is 60 minutes.");
        ReflectionTestUtils.setField(violation, "id", UUID.randomUUID());
        fine = Fine.issue(violation, new BigDecimal("450"), "SEK", null, NOW.minusSeconds(3000));
        ReflectionTestUtils.setField(fine, "id", FINE_ID);
        when(fines.findById(FINE_ID)).thenReturn(Optional.of(fine));
        when(evidence.explain(fine)).thenReturn(new FineExplanation(fine, null, "Storgatan 1", "Storgatan 1", null));
        when(lookups.failuresSince(any(), any())).thenReturn(List.of());
    }

    private PublicFineLookup recorded() {
        ArgumentCaptor<PublicFineLookup> captor = ArgumentCaptor.forClass(PublicFineLookup.class);
        verify(lookups).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void theRightPlateGetsTheFineHoweverItIsTyped() {
        for (String typed : new String[] {"ABC123", "abc123", "abc 123", "ABC-123", " a b c - 1 2 3 "}) {
            PublicFineResponse r = service.lookup(FINE_ID, typed, "203.0.113.7");

            assertThat(r.fineId()).isEqualTo(FINE_ID);
            assertThat(r.status()).isEqualTo("unpaid");
            assertThat(r.amount()).isEqualByComparingTo("450");
            assertThat(r.reasonCode()).isEqualTo("overstay");
        }
    }

    @Test
    void theStatusFollowsTheFine() {
        ReflectionTestUtils.setField(fine, "paid", true);
        assertThat(service.lookup(FINE_ID, "ABC123", null).status()).isEqualTo("paid");

        ReflectionTestUtils.setField(fine, "paid", false);
        ReflectionTestUtils.setField(fine, "voidedAt", NOW);
        assertThat(service.lookup(FINE_ID, "ABC123", null).status()).isEqualTo("voided");
    }

    @Test
    void aWrongPlateAndAnUnknownNumberAreTheSameAnswer() {
        UUID unknown = UUID.fromString("99999999-2222-3333-4444-555555555555");
        when(fines.findById(unknown)).thenReturn(Optional.empty());

        Throwable wrongPlate = org.assertj.core.api.Assertions.catchThrowable(() -> service.lookup(FINE_ID, "XYZ999", null));
        Throwable unknownNumber = org.assertj.core.api.Assertions.catchThrowable(() -> service.lookup(unknown, "ABC123", null));

        assertThat(wrongPlate).isInstanceOf(ResourceNotFoundException.class);
        assertThat(unknownNumber).isInstanceOf(ResourceNotFoundException.class);
        assertThat(((ResourceNotFoundException) wrongPlate).code()).isEqualTo(((ResourceNotFoundException) unknownNumber).code()).isEqualTo("notFound.fine");
        verify(evidence, never()).explain(any());
    }

    @Test
    void everyOutcomeIsRecordedWithoutThePlateAndWithAKeyedAddressHashAtMost() {
        service.lookup(FINE_ID, "ABC123", "203.0.113.7");

        PublicFineLookup row = recorded();
        assertThat(row.getFineId()).isEqualTo(FINE_ID);
        assertThat(row.getOutcome()).isEqualTo(PublicFineLookup.OK);
        assertThat(row.getAt()).isEqualTo(NOW);
        assertThat(row.getClientHash()).hasSize(32).doesNotContain("203.0.113.7").matches("[0-9a-f]{32}");
    }

    @Test
    void aWrongPlateIsRecordedAsNotFoundAndAnUnknownNumberToo() {
        UUID unknown = UUID.randomUUID();
        when(fines.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookup(unknown, "ABC123", null)).isInstanceOf(ResourceNotFoundException.class);

        ArgumentCaptor<PublicFineLookup> captor = ArgumentCaptor.forClass(PublicFineLookup.class);
        verify(lookups).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(PublicFineLookup.NOT_FOUND);
        assertThat(captor.getValue().getFineId()).isEqualTo(unknown);
        assertThat(captor.getValue().getClientHash()).isNull();
    }

    @Test
    void afterFiveWrongPlatesTheNumberIsLockedEvenForTheRightPlateAndEvenWhenItDoesNotExist() {
        UUID unknown = UUID.randomUUID();
        List<Instant> failures = List.of(NOW.minusSeconds(600), NOW.minusSeconds(500), NOW.minusSeconds(400), NOW.minusSeconds(300), NOW.minusSeconds(200));
        when(lookups.failuresSince(any(), any())).thenReturn(failures);
        when(fines.findById(unknown)).thenReturn(Optional.empty());

        Throwable existing = org.assertj.core.api.Assertions.catchThrowable(() -> service.lookup(FINE_ID, "ABC123", null));
        Throwable missing = org.assertj.core.api.Assertions.catchThrowable(() -> service.lookup(unknown, "ABC123", null));

        assertThat(existing).isInstanceOf(TooManyRequestsException.class);
        assertThat(missing).isInstanceOf(TooManyRequestsException.class);
        assertThat(((TooManyRequestsException) existing).code()).isEqualTo("publicLookup.locked");
        // the oldest failure (600 s ago) leaves the 15 minute window in 300 s
        assertThat(((TooManyRequestsException) existing).retryAfter()).isEqualTo(Duration.ofSeconds(300));
        verify(fines, never()).findById(any());
    }

    @Test
    void fourWrongPlatesDoNotLockYet() {
        when(lookups.failuresSince(any(), any())).thenReturn(List.of(NOW.minusSeconds(60), NOW.minusSeconds(50), NOW.minusSeconds(40), NOW.minusSeconds(30)));

        assertThat(service.lookup(FINE_ID, "ABC123", null).fineId()).isEqualTo(FINE_ID);
    }

    @Test
    void theWindowOfTheCountIsTheConfiguredOne() {
        service.lookup(FINE_ID, "ABC123", null);

        verify(lookups).failuresSince(FINE_ID, NOW.minus(Duration.ofMinutes(15)));
    }

    @Test
    void theAddressHashChangesWithTheKeyAndIsNotThePlainHash() {
        PublicLookupProperties salted = new PublicLookupProperties(5, Duration.ofMinutes(15), Duration.ofDays(30), "secret", "0 35 3 * * *", 1000);
        PublicFineService withKey = new PublicFineService(fines, evidence, lookups, salted, Clock.fixed(NOW, ZoneOffset.UTC));

        service.lookup(FINE_ID, "ABC123", "203.0.113.7");
        withKey.lookup(FINE_ID, "ABC123", "203.0.113.7");

        ArgumentCaptor<PublicFineLookup> captor = ArgumentCaptor.forClass(PublicFineLookup.class);
        verify(lookups, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getClientHash()).isNotEqualTo(captor.getAllValues().get(1).getClientHash());
    }
}
