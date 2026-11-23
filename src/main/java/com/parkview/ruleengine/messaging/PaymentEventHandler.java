package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.dto.event.PaymentCompletedEvent;
import com.parkview.ruleengine.service.fine.FineSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payment.completed.fine} from payment-service and marks the fine as paid. The
 * event type is the fine-specific one, so session payments never arrive here. The first payment
 * also publishes {@code fine.paid}; repeats change nothing. Called by {@link AmqpRuleListeners} or {@link RuleKafkaListeners}.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventHandler {

    private final FineSettlementService fineSettlement;

    public void onPaymentCompleted(PaymentCompletedEvent event) {
        fineSettlement.markPaidOnline(event.referenceId(), event.paymentId(), event.amount(), event.currency());
    }
}
