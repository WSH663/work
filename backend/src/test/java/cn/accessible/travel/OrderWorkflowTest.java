package cn.accessible.travel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderWorkflowTest {
    Db db;
    Auth auth;
    OrderWorkflow workflow;

    @BeforeEach void setup() {
        db=mock(Db.class); auth=mock(Auth.class); var settings=mock(SettingsService.class);
        when(settings.number("cancelHours")).thenReturn(new BigDecimal("2")); when(auth.dev()).thenReturn(true);
        workflow=new OrderWorkflow(db,settings,auth,true);
    }

    Map<String,Object> order(String status,String paid,LocalDateTime appointment) {
        var row=new HashMap<String,Object>(); row.put("id",1L); row.put("type","CHARTER"); row.put("status",status);
        row.put("amount",new BigDecimal("100.00")); row.put("paidAmount",new BigDecimal(paid)); row.put("appointmentAt",appointment); row.put("quoteVersion",3);
        return row;
    }
    @SuppressWarnings("unchecked") Map<String,Boolean> actions(Map<String,Object> row) { return (Map<String,Boolean>)row.get("actions"); }

    @Test void paidCancellationCutoffIsSharedByDisplayAndWriteValidation() {
        var row=workflow.present(order("DISPATCHED","100.00",LocalDateTime.now().plusMinutes(30)));
        assertThat(actions(row)).containsEntry("cancel",false).containsEntry("adminCancel",true).containsEntry("start",true);
        assertThat(((Map<?,?>)row.get("cancelRules")).get("cutoffAt")).isNotNull();
        assertThat(row.get("nextStep").toString()).contains("联系客服");
        assertThatThrownBy(()->workflow.requireAction(row,"cancel")).isInstanceOf(Api.Failure.class).hasMessageContaining("联系客服");
    }

    @Test void expiredUnpaidOrderCanBeCancelledButCannotBeAcceptedQuotedOrPaid() {
        for(String status:List.of("PENDING_QUOTE","PENDING_CONFIRM","PENDING_PAYMENT")) {
            var row=workflow.present(order(status,"0",LocalDateTime.now().minusHours(1)));
            assertThat(actions(row)).containsEntry("cancel",true).containsEntry("quote",false).containsEntry("acceptQuote",false).containsEntry("pay",false);
            assertThat(row).containsEntry("appointmentExpired",true).containsEntry("hasPaid",false);
            assertThat(row.get("nextStep").toString()).contains("重新预约");
            workflow.requireAction(row,"cancel");
        }
    }

    @Test void serviceActionsRequireTheEntirePositiveFareToBePaid() {
        for(var entry:Map.of("PENDING_DISPATCH","dispatch","DISPATCHED","start","IN_SERVICE","complete").entrySet()) {
            for(String paid:List.of("0","99.99","100.01")) {
                var row=workflow.present(order(entry.getKey(),paid,LocalDateTime.now().plusDays(1)));
                assertThat(actions(row).get(entry.getValue())).isFalse();
                assertThatThrownBy(()->workflow.requireAction(row,entry.getValue())).isInstanceOf(Api.Failure.class).hasMessageContaining("足额支付");
            }
            var row=workflow.present(order(entry.getKey(),"100.00",LocalDateTime.now().plusDays(1)));
            assertThat(actions(row).get(entry.getValue())).isTrue(); workflow.requireAction(row,entry.getValue());
        }
    }

    @Test void productionAndDisabledPaymentModeDoNotOfferPaymentActions() {
        var pending=order("PENDING_PAYMENT","0",LocalDateTime.now().plusDays(1)); when(auth.dev()).thenReturn(false);
        workflow.present(pending); assertThat(actions(pending)).containsEntry("pay",false);
        assertThat(pending.get("paymentUnavailableReason").toString()).contains("尚未配置");
        when(auth.dev()).thenReturn(true);
        var disabled=new OrderWorkflow(db,mock(SettingsService.class),auth,false);
        // A disabled payment mode still uses the same cancellation configuration.
        when(disabled.settings.number("cancelHours")).thenReturn(new BigDecimal("2"));
        disabled.present(pending); assertThat(actions(pending)).containsEntry("pay",false);
    }

    @Test void financeUsesSuccessfulPaymentHistoryAfterPaidAmountWasClearedByRefund() {
        when(db.list(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("orderId",1L,"totalPaid",new BigDecimal("100.00"),"refunded",new BigDecimal("100.00"))));
        var row=workflow.present(order("REFUNDED","0",LocalDateTime.now().plusDays(1)));
        assertThat((Map<?,?>)row.get("finance")).isEqualTo(Map.of("totalPaid",new BigDecimal("100.00"),"refunded",new BigDecimal("100.00"),"netPaid",new BigDecimal("0.00")));
        assertThat(actions(row).values()).allMatch(value->!value);
    }
}
