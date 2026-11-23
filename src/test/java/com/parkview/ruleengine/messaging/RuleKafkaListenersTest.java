package com.parkview.ruleengine.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkview.platform.domain.Place;
import com.parkview.platform.messaging.InvalidEventException;
import com.parkview.ruleengine.service.cache.RuleCacheService;
import com.parkview.ruleengine.service.cache.SpotCacheService;
import com.parkview.ruleengine.service.fine.FineSettlementService;
import com.parkview.ruleengine.service.violation.DetectionService;
import com.parkview.ruleengine.service.violation.ViolationResolver;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Kafka side of the four events of {@link ListenersTest}: the event type picks the handler, a map event is the business of every instance. */
class RuleKafkaListenersTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final DetectionService detection = mock(DetectionService.class);
    private final FineSettlementService fines = mock(FineSettlementService.class);
    private final ViolationResolver violations = mock(ViolationResolver.class);
    private final SpotCacheService spots = mock(SpotCacheService.class);
    private final RuleCacheService rules = mock(RuleCacheService.class);

    private final RuleKafkaListeners listeners = new RuleKafkaListeners(new VehicleEventHandler(detection), new PaymentEventHandler(fines),
            new SessionEventHandler(violations), new MapEventHandler(spots, rules, violations), mapper, validator);

    private ConsumerRecord<String, String> record(String topic, String eventType, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, "key", json);
        record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("message-id", "row-1".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    void anObservationGoesToTheDetectionService() {
        listeners.onEvent(record("parkview.vehicle", "vehicle.detected",
                "{\"cameraId\":\"c\",\"zoneId\":\"z\",\"plate\":\"ABC123\",\"vehiclePolygon\":[[0,0],[0,1],[1,1]]}"));

        verify(detection).process(any());
    }

    @Test
    void aFinePaymentMarksTheFinePaid() {
        UUID fineId = UUID.randomUUID();

        listeners.onEvent(record("parkview.payment", "payment.completed.fine",
                "{\"paymentId\":\"p-1\",\"referenceId\":\"" + fineId + "\",\"referenceType\":\"FINE\",\"amount\":10,\"currency\":\"SEK\",\"timestamp\":1}"));

        verify(fines).markPaidOnline(fineId, "p-1", BigDecimal.TEN, "SEK");
    }

    @Test
    void aSessionEndResolvesTheViolationsOfThePlate() {
        listeners.onEvent(record("parkview.session", "session.ended", "{\"plate\":\"abc 123\",\"zoneId\":\"z1\"}"));

        verify(violations).resolveEnded("ABC123", Place.of(null, "z1"), null);
    }

    @Test
    void aTypeThisServiceDoesNotConsumeIsLeftAlone() {
        listeners.onEvent(record("parkview.session", "session.started", "{\"plate\":\"ABC123\",\"zoneId\":\"z1\"}"));
        listeners.onEvent(record("parkview.vehicle", "detection.recorded", "{}"));
        listeners.onEvent(record("parkview.payment", "payment.completed.session", "{}"));

        verifyNoInteractions(detection, fines, violations);
    }

    @Test
    void anInvalidPayloadIsRefusedForGoodAndNeverReachesAService() {
        assertThatThrownBy(() -> listeners.onEvent(record("parkview.session", "session.ended", "{\"zoneId\":\"z1\"}")))
                .isInstanceOf(InvalidEventException.class);

        verifyNoInteractions(violations);
    }

    @Test
    void aMapEventEvictsTheCachesOfTheSiteOfItsZone() {
        when(rules.resolve(Place.of(null, "z1"))).thenReturn(Place.of("s1", "z1"));

        listeners.onMapEvent(record("parkview.mapping", "map.updated",
                "{\"zoneId\":\"z1\",\"versionNumber\":7,\"spotCount\":12,\"changeReason\":\"rollback\",\"timestamp\":1}"));

        verify(spots).evict("s1");
        verify(rules).evict(Place.of("s1", "z1"));
        verify(violations).resolveOfRemovedSpots("s1");
    }

    @Test
    void theWorkListenerAndTheCacheListenerDoNotSeeEachOthersEvents() {
        listeners.onMapEvent(record("parkview.vehicle", "vehicle.detected",
                "{\"cameraId\":\"c\",\"zoneId\":\"z\",\"vehiclePolygon\":[[0,0],[0,1],[1,1]]}"));
        listeners.onEvent(record("parkview.mapping", "map.updated", "{\"zoneId\":\"z1\",\"versionNumber\":1}"));

        verifyNoInteractions(detection, spots, rules);
    }

    @Test
    void theMapListenerHasAGroupOfItsOwnAndStartsAtTheEndOfTheTopic() throws Exception {
        KafkaListener maps = RuleKafkaListeners.class.getDeclaredMethod("onMapEvent", ConsumerRecord.class).getAnnotation(KafkaListener.class);
        KafkaListener work = RuleKafkaListeners.class.getDeclaredMethod("onEvent", ConsumerRecord.class).getAnnotation(KafkaListener.class);

        assertThat(maps.groupId()).isEqualTo("#{@parkviewInstanceGroupId}");
        assertThat(maps.properties()).containsExactly("auto.offset.reset=latest");
        assertThat(maps.topics()).containsExactly("parkview.mapping");
        assertThat(work.groupId()).as("the work shares the group of the application").isEmpty();
        assertThat(work.topics()).containsExactlyInAnyOrder("parkview.vehicle", "parkview.session", "parkview.payment");
    }

    @Test
    void theKafkaListenersExistOnlyWhileTheServiceReadsFromKafkaAndTheAmqpOnesOnlyWhileItReadsFromRabbit() {
        ConditionalOnProperty kafka = RuleKafkaListeners.class.getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty amqp = AmqpRuleListeners.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(kafka.havingValue()).isEqualTo("kafka");
        assertThat(kafka.matchIfMissing()).isFalse();
        assertThat(amqp.havingValue()).isEqualTo("amqp");
        assertThat(amqp.matchIfMissing()).as("RabbitMQ stays the default until phase 4").isTrue();
    }
}
