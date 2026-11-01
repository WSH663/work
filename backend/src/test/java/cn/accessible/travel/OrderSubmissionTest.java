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
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:submissions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=submission-admin","app.admin-password=SubmissionAdmin123!","app.dev-payments-enabled=true"})
class OrderSubmissionTest {
    @Autowired Db db;
    @Autowired OrderService orders;
    @MockitoBean MapService maps;
    long user;
    long admin;

    @BeforeEach void setup() {
        for(String table:List.of("order_logs","payments","commissions","withdrawals","messages","orders","passengers","addresses","auth_tokens","audit_logs")) db.update("DELETE FROM "+table);
        db.update("UPDATE users SET referrer_id=NULL"); db.update("DELETE FROM users WHERE role='USER'");
        user=db.insert("INSERT INTO users(nickname,invite_code) VALUES (?,?)","订单提交测试","SUBMISSION-CUSTOMER");
        admin=Inputs.longValue(db.one("SELECT id FROM users WHERE username='submission-admin'"),"id");
        when(maps.route(anyString(),anyString())).thenReturn(new MapService.Route(new BigDecimal("10.00"),25,"TEST_ROUTE"));
    }
    Map<String,Object> booking(String type,String key) {
        var body=new HashMap<String,Object>(); body.put("type",type); body.put("requestKey",key); body.put("vehicleId",1);
        body.put("origin","120.123,30.012"); body.put("destination","120.122,30.059"); body.put("originName","上车地点"); body.put("destinationName","下车地点");
        body.put("appointmentAt",LocalDateTime.now().plusDays(1).withNano(0).toString()); body.put("passengerName","示例乘客"); body.put("passengerPhone","13800138000");
        body.put("hours",4); body.put("itinerary","就医往返，需要轮椅辅助"); return body;
    }
    void expectFailure(Runnable operation,int status,String message) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(Api.Failure.class,failure->{assertThat(failure.status).isEqualTo(status);assertThat(failure.getMessage()).contains(message);});
    }

    @Test void concurrentRetriesReuseAnOrderWithoutRecalculatingItsRoute() throws Exception {
        var body=booking("RIDE","stable-draft-key"); var pool=Executors.newFixedThreadPool(2); var barrier=new CyclicBarrier(2);
        try {
            Callable<Long> create=()->{barrier.await();return Inputs.longValue(orders.create(user,body),"id");};
            var results=pool.invokeAll(List.of(create,create)); assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
            assertThat(db.count("SELECT COUNT(*) FROM orders WHERE user_id=?",user)).isEqualTo(1); verify(maps,times(1)).route(anyString(),anyString());
            orders.create(user,Map.of("requestKey","stable-draft-key")); verify(maps,times(1)).route(anyString(),anyString());
        } finally { pool.shutdownNow(); }
    }

    @Test void staleOrMissingQuoteVersionsCannotConfirmAnUpdatedFare() {
        long id=Inputs.longValue(orders.create(user,booking("CHARTER","quote-draft")),"id");
        int oldVersion=Inputs.number(orders.quote(id,admin,Map.of("amount",100)),"quoteVersion").intValue();
        var latest=orders.quote(id,admin,Map.of("amount",200)); int latestVersion=Inputs.number(latest,"quoteVersion").intValue(); assertThat(latestVersion).isEqualTo(oldVersion+1);
        expectFailure(()->orders.accept(id,user,Map.of()),400,"最新报价");
        expectFailure(()->orders.accept(id,user,Map.of("quoteVersion",oldVersion)),409,"更新报价");
        assertThat(db.one("SELECT status FROM orders WHERE id=?",id)).containsEntry("status","PENDING_CONFIRM");
        var accepted=orders.accept(id,user,Map.of("quoteVersion",latestVersion)); assertThat(accepted).containsEntry("status","PENDING_PAYMENT"); assertThat(Inputs.number(accepted,"amount")).isEqualByComparingTo("200.00");
    }

    @Test void expiredUnpaidOrdersRetainTheirStateAndCanStillBeCancelled() {
        long ride=Inputs.longValue(orders.create(user,booking("RIDE","expired-ride")),"id");
        db.update("UPDATE orders SET appointment_at=? WHERE id=?",LocalDateTime.now().minusMinutes(1),ride);
        expectFailure(()->orders.pay(ride,user),400,"预约时间已过"); assertThat(db.one("SELECT status FROM orders WHERE id=?",ride)).containsEntry("status","PENDING_PAYMENT");
        assertThat(orders.cancel(ride,user,Map.of("reason","重新预约"))).containsEntry("status","CANCELLED");
        long charter=Inputs.longValue(orders.create(user,booking("CHARTER","expired-charter")),"id");
        var quote=orders.quote(charter,admin,Map.of("amount",100));
        db.update("UPDATE orders SET appointment_at=? WHERE id=?",LocalDateTime.now().minusMinutes(1),charter);
        expectFailure(()->orders.quote(charter,admin,Map.of("amount",200)),400,"预约时间已过");
        expectFailure(()->orders.accept(charter,user,Map.of("quoteVersion",quote.get("quoteVersion"))),400,"预约时间已过");
        assertThat(db.one("SELECT status,amount FROM orders WHERE id=?",charter)).containsEntry("status","PENDING_CONFIRM");
    }

    @Test void cancellationReviewRequiresAnExplicitDecisionAndRejectionReason() {
        long id=Inputs.longValue(orders.create(user,booking("RIDE","cancel-review")),"id"); orders.pay(id,user); orders.cancel(id,user,Map.of("reason","计划有变"));
        expectFailure(()->orders.cancelReview(id,admin,Map.of()),400,"请选择");
        expectFailure(()->orders.cancelReview(id,admin,Map.of("approved",false)),400,"reason不能为空");
        assertThat(orders.cancelReview(id,admin,Map.of("approved",false,"reason","客户确认继续出行"))).containsEntry("status","PENDING_DISPATCH");
    }
}
