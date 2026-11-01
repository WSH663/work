package cn.accessible.travel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:commission-delay;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=commission-admin","app.admin-password=CommissionAdmin123!","app.dev-payments-enabled=true","app.commission-settlement-initial-delay-ms=3600000"})
class CommissionDelayTest {
    @Autowired Db db;
    @Autowired OrderService orders;
    @Autowired CommissionService commissions;
    @Autowired PromotionService promotion;
    @Autowired SettingsService settings;
    @MockitoBean MapService maps;
    long promoter;
    long customer;
    long admin;

    @BeforeEach void setup() {
        for(String table:List.of("order_logs","payments","commissions","withdrawals","messages","orders","passengers","addresses","auth_tokens","audit_logs"))db.update("DELETE FROM "+table);
        db.update("UPDATE users SET referrer_id=NULL");db.update("DELETE FROM users WHERE role='USER'");
        promoter=db.insert("INSERT INTO users(nickname,invite_code) VALUES (?,?)","推广人","DELAY-PROMOTER");
        customer=db.insert("INSERT INTO users(nickname,invite_code,referrer_id) VALUES (?,?,?)","乘客","DELAY-CUSTOMER",promoter);
        admin=Inputs.longValue(db.one("SELECT id FROM users WHERE username='commission-admin'"),"id");
        // Settings validation requires a configured support contact; this is a test-only value.
        settings.save(Map.of("servicePhone","13800138000"));
        when(maps.route(anyString(),anyString())).thenReturn(new MapService.Route(new BigDecimal("10.00"),25,"TEST_ROUTE"));
    }

    long completedOrder() {
        var body=new HashMap<String,Object>();body.put("type","RIDE");body.put("requestKey",UUID.randomUUID().toString());body.put("vehicleId",1);
        body.put("origin","120.123,30.012");body.put("destination","120.122,30.059");body.put("originName","上车点");body.put("destinationName","下车点");
        body.put("appointmentAt",LocalDateTime.now().plusDays(1).toString());body.put("passengerName","示例乘客");body.put("passengerPhone","13800138000");
        long id=Inputs.longValue(orders.create(customer,body),"id");orders.pay(id,customer);
        orders.dispatch(id,admin,Map.of("driverName","司机","driverPhone","13900139000","plateNumber","示例A12345"));orders.start(id,admin);orders.complete(id,admin);return id;
    }
    Map<String,Object> account(){return db.one("SELECT * FROM users WHERE id=?",promoter);}

    @Test void completingAnOrderCreatesThirtyDayPendingCommissionWithoutCreditingAvailableFunds() {
        LocalDateTime earliest=LocalDateTime.now().plusDays(30);long id=completedOrder();LocalDateTime latest=LocalDateTime.now().plusDays(30);
        var commission=db.one("SELECT * FROM commissions WHERE order_id=?",id);assertThat(commission).containsEntry("status","PENDING").containsEntry("settledAt",null);
        assertThat((LocalDateTime)commission.get("eligibleAt")).isBetween(earliest,latest);
        assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("0.00");assertThat(commissions.settleDue()).isZero();
        var summary=promotion.summary(promoter);assertThat(Inputs.number(summary,"pendingAmount")).isEqualByComparingTo("3.15");assertThat(Inputs.number(summary,"totalCommission")).isEqualByComparingTo("3.15");
        assertThatThrownBy(()->promotion.apply(promoter,Map.of("amount",10,"accountName","推广人","accountNo","test-account"))).isInstanceOf(Api.Failure.class).hasMessageContaining("余额不足");
    }

    @Test void concurrentSettlementRunsCreditAnEligibleCommissionOnlyOnce() throws Exception {
        long id=completedOrder();db.update("UPDATE commissions SET eligible_at=? WHERE order_id=?",LocalDateTime.now().minusSeconds(1),id);
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {
            Callable<Integer> settle=()->{barrier.await();return commissions.settleDue();};var results=pool.invokeAll(List.of(settle,settle));
            assertThat(results.get(0).get()+results.get(1).get()).isEqualTo(1);
        } finally {pool.shutdownNow();}
        assertThat(commissions.settleDue()).isZero();assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("3.15");
        var commission=db.one("SELECT * FROM commissions WHERE order_id=?",id);assertThat(commission).containsEntry("status","SETTLED");assertThat(commission.get("settledAt")).isNotNull();
        assertThat(Inputs.number(promotion.summary(promoter),"pendingAmount")).isEqualByComparingTo("0.00");
        assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=? AND title='佣金已结算'",promoter)).isEqualTo(1);
    }

    @Test void refundWithinThirtyDaysReversesPendingCommissionWithoutRequiringAvailableFunds() {
        long id=completedOrder();var refunded=orders.refund(id,admin,Map.of("reason","售后全额退款"));
        assertThat(refunded).containsEntry("status","REFUNDED");assertThat(db.one("SELECT * FROM commissions WHERE order_id=?",id)).containsEntry("status","REVERSED").containsEntry("settledAt",null);
        assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("0.00");assertThat(Inputs.number(account(),"frozenAmount")).isEqualByComparingTo("0.00");
        db.update("UPDATE commissions SET eligible_at=? WHERE order_id=?",LocalDateTime.now().minusSeconds(1),id);assertThat(commissions.settleDue()).isZero();
        assertThat(Inputs.number(promotion.summary(promoter),"pendingAmount")).isEqualByComparingTo("0.00");assertThat(Inputs.number(promotion.summary(promoter),"totalCommission")).isEqualByComparingTo("0.00");
    }

    @Test void settlementRechecksOrderStateBeforeCreditingFunds() {
        long id=completedOrder();db.update("UPDATE commissions SET eligible_at=? WHERE order_id=?",LocalDateTime.now().minusSeconds(1),id);
        db.update("UPDATE orders SET status='REFUNDING' WHERE id=?",id);assertThat(commissions.settleDue()).isZero();assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("0.00");
        assertThat(db.one("SELECT status FROM commissions WHERE order_id=?",id)).containsEntry("status","PENDING");
    }

    long approvedWithdrawal() {
        db.update("UPDATE users SET available_amount=20 WHERE id=?",promoter);
        long id=Inputs.longValue(promotion.apply(promoter,Map.of("amount",10,"accountName","推广人","accountNo","test-account")),"id");
        promotion.review(id,admin,Map.of("approved",true));return id;
    }

    @Test void concurrentWithdrawalRetriesFreezeFundsOnlyOnceAndCanRecoverAfterRulesChange() throws Exception {
        db.update("UPDATE users SET available_amount=40 WHERE id=?",promoter);
        var body=Map.<String,Object>of("amount",10,"accountName","推广人","accountNo","test-account","requestKey","withdrawal-same-attempt");
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {
            Callable<Long> apply=()->{barrier.await();return Inputs.longValue(promotion.apply(promoter,body),"id");};
            var results=pool.invokeAll(List.of(apply,apply));long id=results.get(0).get();assertThat(results.get(1).get()).isEqualTo(id);
            assertThat(db.count("SELECT COUNT(*) FROM withdrawals WHERE user_id=?",promoter)).isEqualTo(1);
            assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("30.00");assertThat(Inputs.number(account(),"frozenAmount")).isEqualByComparingTo("10.00");
            BigDecimal previousMinimum=settings.number("withdrawalMinimum");
            try {settings.save(Map.of("withdrawalMinimum",100));assertThat(Inputs.longValue(promotion.apply(promoter,Map.of("requestKey","withdrawal-same-attempt")),"id")).isEqualTo(id);}
            finally {settings.save(Map.of("withdrawalMinimum",previousMinimum));}
            assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=? AND title='提现申请已提交'",promoter)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void withdrawalRequestKeysAreScopedToUserAndReviewsNeedExplicitDecisions() {
        db.update("UPDATE users SET available_amount=30 WHERE id IN (?,?)",promoter,customer);
        var body=Map.<String,Object>of("amount",10,"accountName","推广人","accountNo","test-account","requestKey","shared-key");
        long first=Inputs.longValue(promotion.apply(promoter,body),"id"),second=Inputs.longValue(promotion.apply(customer,body),"id");
        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(()->promotion.apply(promoter,Map.of("requestKey","bad key"))).isInstanceOf(Api.Failure.class);
        assertThatThrownBy(()->promotion.review(first,admin,Map.of())).isInstanceOf(Api.Failure.class).hasMessageContaining("请选择");
        assertThatThrownBy(()->promotion.review(first,admin,Map.of("approved",false))).isInstanceOf(Api.Failure.class).hasMessageContaining("remark不能为空");
        assertThat(promotion.review(first,admin,Map.of("approved",false,"remark","收款信息需核对"))).containsEntry("status","REJECTED");
        assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("30.00");
    }

    @Test void cancellingAnUnpaidApprovedWithdrawalRequiresAReasonAndUnfreezesItOnce() {
        long id=approvedWithdrawal();
        assertThatThrownBy(()->promotion.cancel(id,admin,Map.of())).isInstanceOf(Api.Failure.class).hasMessageContaining("reason不能为空");
        assertThat(Inputs.number(account(),"frozenAmount")).isEqualByComparingTo("10.00");
        var cancelled=promotion.cancel(id,admin,Map.of("reason","收款账号有误，尚未转账"));
        assertThat(cancelled).containsEntry("status","REJECTED");assertThat(cancelled.get("remark").toString()).contains("收款账号有误");
        assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("20.00");assertThat(Inputs.number(account(),"frozenAmount")).isEqualByComparingTo("0.00");
        assertThatThrownBy(()->promotion.cancel(id,admin,Map.of("reason","重复撤销"))).isInstanceOf(Api.Failure.class);
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='WITHDRAWAL_CANCEL' AND target=?","提现 "+id)).isEqualTo(1);
    }

    @Test void concurrentPaymentRegistrationAndCancellationHaveOnlyOneWinner() throws Exception {
        long id=approvedWithdrawal();var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        Callable<Boolean> paid=()->{barrier.await();try{promotion.paid(id,admin,Map.of("paymentReference","BATCH-TRANSFER-REF"));return true;}catch(Api.Failure failure){assertThat(failure.status).isEqualTo(400);return false;}};
        Callable<Boolean> cancelled=()->{barrier.await();try{promotion.cancel(id,admin,Map.of("reason","尚未转账，撤销审核"));return true;}catch(Api.Failure failure){assertThat(failure.status).isEqualTo(400);return false;}};
        try {var results=pool.invokeAll(List.of(paid,cancelled));assertThat(results.get(0).get()^results.get(1).get()).isTrue();}finally{pool.shutdownNow();}
        String status=db.one("SELECT status FROM withdrawals WHERE id=?",id).get("status").toString();assertThat(status).isIn("PAID","REJECTED");
        assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("PAID".equals(status)?"10.00":"20.00");assertThat(Inputs.number(account(),"frozenAmount")).isEqualByComparingTo("0.00");
        assertThatThrownBy(()->promotion.cancel(id,admin,Map.of("reason","不能再次撤销"))).isInstanceOf(Api.Failure.class);
    }

    @Test void aPaidWithdrawalCannotBeCancelledOrCreditedBack() {
        long id=approvedWithdrawal();promotion.paid(id,admin,Map.of("paymentReference","REGISTERED-TRANSFER"));
        assertThatThrownBy(()->promotion.cancel(id,admin,Map.of("reason","已付后误操作"))).isInstanceOf(Api.Failure.class).hasMessageContaining("尚未付款");
        assertThat(db.one("SELECT status FROM withdrawals WHERE id=?",id)).containsEntry("status","PAID");assertThat(Inputs.number(account(),"availableAmount")).isEqualByComparingTo("10.00");assertThat(Inputs.number(account(),"frozenAmount")).isEqualByComparingTo("0.00");
    }

    @Test void commissionRateRejectsMorePrecisionThanTheOrderSnapshotCanStore() {
        BigDecimal previous=settings.number("commissionRate");
        assertThatThrownBy(()->settings.save(Map.of("commissionRate",new BigDecimal("0.12345")))).isInstanceOfSatisfying(Api.Failure.class,failure->assertThat(failure.status).isEqualTo(400)).hasMessageContaining("4位小数");
        assertThat(settings.number("commissionRate")).isEqualByComparingTo(previous);
        try {settings.save(Map.of("commissionRate",new BigDecimal("0.1234")));assertThat(settings.number("commissionRate")).isEqualByComparingTo("0.1234");}
        finally {settings.save(Map.of("commissionRate",previous));}
    }

    @Test void incompatibleBookingSettingsDoNotSaveAnUnbookableWindow() {
        var previous=settings.all();
        assertThatThrownBy(()->settings.save(Map.of("advanceMinutes",10080,"maxAdvanceDays",1))).isInstanceOf(Api.Failure.class).hasMessageContaining("不能超过");
        assertThat(settings.all().get("advanceMinutes")).isEqualTo(previous.get("advanceMinutes"));
        assertThat(settings.all().get("maxAdvanceDays")).isEqualTo(previous.get("maxAdvanceDays"));
    }
}
