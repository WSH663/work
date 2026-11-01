package cn.accessible.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:business;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=test-admin","app.admin-password=TestAdmin123!","app.dev-payments-enabled=true"})
@AutoConfigureMockMvc
class BusinessFlowTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;@Autowired Db db;@Autowired OrderService orders;@Autowired PromotionService promotion;@Autowired SettingsService settings;@Autowired AdminController admins;@Autowired CommissionService commissions;
    @MockitoBean MapService maps;
    String admin;
    @BeforeEach void setup() throws Exception {
        for(String table:List.of("order_logs","payments","commissions","withdrawals","messages","orders","passengers","addresses","auth_tokens","audit_logs"))db.update("DELETE FROM "+table);
        db.update("UPDATE users SET referrer_id=NULL");
        db.update("DELETE FROM users WHERE username IS NULL OR username <> 'test-admin'");
        when(maps.route(anyString(),anyString())).thenReturn(new MapService.Route(new BigDecimal("10.00"),25,"TEST_ROUTE"));
        // Synthetic support contact belongs to this isolated fixture, never production seed data.
        settings.save(Map.of("servicePhone","13800138000","withdrawalMinimum",1,"advanceMinutes",60,"commissionRate",0.05,"cancelHours",2));
        admin=postData("/api/auth/admin/login",Map.of("username","test-admin","password","TestAdmin123!"),null,200).path("token").asText();
    }
    JsonNode postData(String path,Object data,String token,int expected) throws Exception {var request=post(path).contentType("application/json").content(json.writeValueAsString(data));if(token!=null)request.header("Authorization","Bearer "+token);String response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();return json.readTree(response).path("data");}
    JsonNode user(String name,String invite) throws Exception {var body=new HashMap<String,Object>();body.put("nickname",name);body.put("phone","13800138000");if(invite!=null)body.put("inviteCode",invite);return postData("/api/auth/dev-login",body,null,200);}
    Map<String,Object> booking(String type){var data=new HashMap<String,Object>();data.put("type",type);data.put("vehicleId",1);data.put("originName","示例车站");data.put("destinationName","示例医院");data.put("origin","120.123,30.012");data.put("destination","120.122,30.059");data.put("appointmentAt",LocalDateTime.now().plusDays(1).withNano(0).toString());data.put("passengerName","示例乘客");data.put("passengerPhone","13800138000");data.put("amount",0.01);data.put("hours",4);data.put("itinerary","就医往返，需要轮椅辅助");return data;}
    void finish(long id,String token) throws Exception {postData("/api/orders/"+id+"/pay",Map.of(),token,200);postData("/api/admin/orders/"+id+"/dispatch",Map.of("driverName","示例司机","driverPhone","13900139000","plateNumber","示例A12345"),admin,200);postData("/api/admin/orders/"+id+"/start",Map.of(),admin,200);postData("/api/admin/orders/"+id+"/complete",Map.of(),admin,200);}

    @Test void rideRepricesAndChecksOwnershipAndState() throws Exception {
        JsonNode u=user("甲",null),other=user("乙",null);String token=u.path("token").asText();var order=postData("/api/orders",booking("RIDE"),token,200);long id=order.path("id").asLong();
        assertThat(order.path("amount").decimalValue()).isEqualByComparingTo("63.00");
        mvc.perform(get("/api/orders/"+id).header("Authorization","Bearer "+other.path("token").asText())).andExpect(status().isNotFound());
        postData("/api/orders/"+id+"/pay",Map.of(),other.path("token").asText(),404);
        postData("/api/admin/orders/"+id+"/start",Map.of(),admin,400);
        var paid=postData("/api/orders/"+id+"/pay",Map.of(),token,200);assertThat(paid.path("mode").asText()).isEqualTo("DEV");
        postData("/api/orders/"+id+"/pay",Map.of(),token,400);assertThat(db.count("SELECT COUNT(*) FROM payments WHERE order_id=?",id)).isEqualTo(1);
        db.update("UPDATE orders SET appointment_at=? WHERE id=?",LocalDateTime.now().plusMinutes(30),id);postData("/api/orders/"+id+"/cancel",Map.of("reason","临近取消"),token,400);db.update("UPDATE orders SET appointment_at=? WHERE id=?",LocalDateTime.now().plusDays(1),id);
        postData("/api/orders/"+id+"/cancel",Map.of("reason","行程改变"),token,200);postData("/api/admin/orders/"+id+"/cancel-review",Map.of("approved",true),admin,200);postData("/api/admin/orders/"+id+"/refund",Map.of("reason","审核取消"),admin,200);
        assertThat(db.one("SELECT status,paid_amount FROM orders WHERE id=?",id)).containsEntry("status","REFUNDED");postData("/api/admin/orders/"+id+"/refund",Map.of("reason","重复"),admin,400);
    }
    @Test void charterQuoteCommissionFreezeRefundAndWithdrawalAreConsistent() throws Exception {
        JsonNode ref=user("推广人",null);String refToken=ref.path("token").asText();String invite=ref.path("user").path("inviteCode").asText();long refId=ref.path("user").path("id").asLong();JsonNode customer=user("被推广人",invite);String token=customer.path("token").asText();
        var order=postData("/api/orders",booking("CHARTER"),token,200);long id=order.path("id").asLong();assertThat(order.path("status").asText()).isEqualTo("PENDING_QUOTE");postData("/api/orders/"+id+"/pay",Map.of(),token,400);
        JsonNode quote=postData("/api/admin/orders/"+id+"/quote",Map.of("amount",200),admin,200);postData("/api/orders/"+id+"/accept-quote",Map.of("quoteVersion",quote.path("quoteVersion").asInt()),token,200);finish(id,token);
        assertThat(Inputs.number(db.one("SELECT * FROM users WHERE id=?",refId),"availableAmount")).isEqualByComparingTo("0.00");
        assertThat(db.one("SELECT status FROM commissions WHERE order_id=?",id).get("status")).isEqualTo("PENDING");
        db.update("UPDATE commissions SET eligible_at=? WHERE order_id=?",LocalDateTime.now().minusSeconds(1),id);commissions.settleDue();
        assertThat(Inputs.number(db.one("SELECT * FROM users WHERE id=?",refId),"availableAmount")).isEqualByComparingTo("10.00");
        JsonNode withdrawal=postData("/api/me/withdrawals",Map.of("amount",6,"accountName","推广人","accountNo","test-account"),refToken,200);long wid=withdrawal.path("id").asLong();
        postData("/api/admin/orders/"+id+"/refund",Map.of("reason","售后退款"),admin,400);assertThat(db.one("SELECT status FROM orders WHERE id=?",id).get("status")).isEqualTo("COMPLETED");
        postData("/api/admin/withdrawals/"+wid+"/review",Map.of("approved",false,"remark","撤销提现"),admin,200);postData("/api/admin/withdrawals/"+wid+"/review",Map.of("approved",false),admin,400);
        postData("/api/admin/orders/"+id+"/refund",Map.of("reason","售后退款"),admin,200);assertThat(Inputs.number(db.one("SELECT * FROM users WHERE id=?",refId),"availableAmount")).isEqualByComparingTo("0.00");assertThat(db.one("SELECT status FROM commissions WHERE order_id=?",id).get("status")).isEqualTo("REVERSED");
    }
    @Test void concurrentPaymentsAndWithdrawalsCannotDoubleSpend() throws Exception {
        JsonNode u=user("并发测试",null);long uid=u.path("user").path("id").asLong();String token=u.path("token").asText();long id=postData("/api/orders",booking("RIDE"),token,200).path("id").asLong();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var barrier=new CyclicBarrier(2);Callable<Boolean> payment=()->{barrier.await();try{orders.pay(id,uid);return true;}catch(Api.Failure failure){assertThat(failure.status).isEqualTo(400);return false;}};
            var results=pool.invokeAll(List.of(payment,payment));assertThat(results.get(0).get()^results.get(1).get()).isTrue();assertThat(db.count("SELECT COUNT(*) FROM payments WHERE order_id=?",id)).isEqualTo(1);
            db.update("UPDATE users SET available_amount=10 WHERE id=?",uid);var withdrawBarrier=new CyclicBarrier(2);Callable<Boolean> withdrawal=()->{withdrawBarrier.await();try{promotion.apply(uid,Map.of("amount",8,"accountName","示例收款人","accountNo","account"));return true;}catch(Api.Failure failure){return false;}};
            var withdrawals=pool.invokeAll(List.of(withdrawal,withdrawal));assertThat(withdrawals.get(0).get()^withdrawals.get(1).get()).isTrue();var profile=db.one("SELECT * FROM users WHERE id=?",uid);assertThat(Inputs.number(profile,"availableAmount")).isEqualByComparingTo("2.00");assertThat(Inputs.number(profile,"frozenAmount")).isEqualByComparingTo("8.00");
            long wid=((Number)db.one("SELECT id FROM withdrawals WHERE user_id=?",uid).get("id")).longValue();postData("/api/admin/withdrawals/"+wid+"/review",Map.of("approved",true),admin,200);postData("/api/admin/withdrawals/"+wid+"/paid",Map.of("paymentReference","TEST-PAID-001"),admin,200);postData("/api/admin/withdrawals/"+wid+"/paid",Map.of("paymentReference","TEST-PAID-001"),admin,400);assertThat(Inputs.number(db.one("SELECT * FROM users WHERE id=?",uid),"frozenAmount")).isEqualByComparingTo("0.00");
        }finally{pool.shutdownNow();}
    }
    @Test void roleBoundariesAndDisabledSessionsAreEnforced() throws Exception {
        postData("/api/admin/admins",Map.of("username","operator-test","password","Operator123!","nickname","运营","role","OPERATOR"),admin,200);
        postData("/api/admin/admins",Map.of("username","finance-test","password","Finance123!","nickname","财务","role","FINANCE"),admin,200);
        String operator=postData("/api/auth/admin/login",Map.of("username","operator-test","password","Operator123!"),null,200).path("token").asText();String finance=postData("/api/auth/admin/login",Map.of("username","finance-test","password","Finance123!"),null,200).path("token").asText();
        mvc.perform(get("/api/admin/payments").header("Authorization","Bearer "+operator)).andExpect(status().isForbidden());mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+finance)).andExpect(status().isForbidden());postData("/api/admin/settings",Map.of("notice","unauthorized"),operator,403);
        JsonNode u=user("用户",null);String token=u.path("token").asText();mvc.perform(get("/api/admin/orders").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        assertThat(postData("/api/me",Map.of("nickname","兼容微信更新"),token,200).path("nickname").asText()).isEqualTo("兼容微信更新");
        mvc.perform(patch("/api/admin/users/"+u.path("user").path("id").asLong()).contentType("application/json").content("{\"status\":\"DISABLED\"}").header("Authorization","Bearer "+admin)).andExpect(status().isOk());mvc.perform(get("/api/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void publishedServiceAndNoticeContentIsAvailableInPublicHome() throws Exception {
        JsonNode service=postData("/api/admin/content",Map.of("kind","SERVICE","title","轮椅预约须知","body","请提前说明轮椅尺寸。","sortOrder",0,"enabled",true),admin,200);
        JsonNode disabled=postData("/api/admin/content",Map.of("kind","SERVICE","title","内部未发布服务","body","草稿","sortOrder",0,"enabled",false),admin,200);
        JsonNode notice=postData("/api/admin/content",Map.of("kind","NOTICE","title","国庆预约公告","body","请提前预约。","sortOrder",0,"enabled",true),admin,200);
        String response=mvc.perform(get("/api/public/home")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();JsonNode home=json.readTree(response).path("data");
        assertThat(home.path("services").get(0).path("id").asLong()).isEqualTo(service.path("id").asLong());
        assertThat(home.path("services").findValuesAsText("title")).contains("轮椅预约须知").doesNotContain("内部未发布服务");
        assertThat(home.path("notices").get(0).path("id").asLong()).isEqualTo(notice.path("id").asLong());
    }
    @Test void fullSettingsFormSavesAllFieldsAndRecordsCompleteAuditTarget() throws Exception {
        String response=mvc.perform(get("/api/admin/settings").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode before=json.readTree(response).path("data");
        Map<String,Object> form=json.convertValue(before,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        assertThat(form).hasSize(10);String target=String.join(",",form.keySet());assertThat(target.length()).isGreaterThan(120);
        form.put("notice","全量设置保存回归：请提前预约预约出行服务。");JsonNode saved=postData("/api/admin/settings",form,admin,200);
        assertThat(saved.path("notice").asText()).isEqualTo(form.get("notice"));
        before.fields().forEachRemaining(entry->{if(!entry.getKey().equals("notice"))assertThat(saved.get(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());});
        var audit=db.one("SELECT * FROM audit_logs WHERE action='SETTINGS_UPDATE' ORDER BY id DESC LIMIT 1");
        assertThat(audit.get("target")).isEqualTo(target);assertThat(audit.get("actorName")).isEqualTo("平台管理员");
        JsonNode reloaded=json.readTree(mvc.perform(get("/api/admin/settings").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(reloaded).isEqualTo(saved);
    }
    @Test void customerSupportCanCancelNearAppointmentWithoutBypassingRefundControls() throws Exception {
        postData("/api/admin/admins",Map.of("username","cancel-operator","password","Operator123!","nickname","客服","role","OPERATOR"),admin,200);
        String operator=postData("/api/auth/admin/login",Map.of("username","cancel-operator","password","Operator123!"),null,200).path("token").asText();
        JsonNode u=user("人工取消测试",null);String token=u.path("token").asText();long uid=u.path("user").path("id").asLong();
        long unpaid=postData("/api/orders",booking("CHARTER"),token,200).path("id").asLong();assertThat(postData("/api/admin/orders/"+unpaid+"/cancel",Map.of("reason","客户来电取消"),operator,200).path("status").asText()).isEqualTo("CANCELLED");
        long paid=postData("/api/orders",booking("RIDE"),token,200).path("id").asLong();postData("/api/orders/"+paid+"/pay",Map.of(),token,200);db.update("UPDATE orders SET appointment_at=? WHERE id=?",LocalDateTime.now().plusMinutes(10),paid);
        postData("/api/orders/"+paid+"/cancel",Map.of("reason","临近取消"),token,400);
        postData("/api/admin/orders/"+paid+"/cancel",Map.of("reason","越权取消"),token,403);
        assertThat(postData("/api/admin/orders/"+paid+"/cancel",Map.of("reason","客户来电取消"),operator,200).path("status").asText()).isEqualTo("REFUNDING");
        assertThat(db.count("SELECT COUNT(*) FROM payments WHERE order_id=? AND type='REFUND'",paid)).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='ADMIN_CANCEL'")).isEqualTo(2);assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=? AND content LIKE ?",uid,"%客户来电取消%")).isEqualTo(2);
        postData("/api/admin/orders/"+paid+"/refund",Map.of("reason","财务确认退款"),operator,403);postData("/api/admin/orders/"+paid+"/refund",Map.of("reason","财务确认退款"),admin,200);postData("/api/admin/orders/"+paid+"/cancel",Map.of("reason","重复取消"),operator,400);
        long serving=postData("/api/orders",booking("RIDE"),token,200).path("id").asLong();postData("/api/orders/"+serving+"/pay",Map.of(),token,200);postData("/api/admin/orders/"+serving+"/dispatch",Map.of("driverName","司机","driverPhone","13900139000","plateNumber","示例A12345"),operator,200);postData("/api/admin/orders/"+serving+"/start",Map.of(),operator,200);
        postData("/api/admin/orders/"+serving+"/cancel",Map.of("reason","服务中不可从此取消"),operator,400);postData("/api/admin/orders/"+serving+"/complete",Map.of(),operator,200);postData("/api/admin/orders/"+serving+"/cancel",Map.of("reason","已完成不可从此取消"),operator,400);
    }
    @Test void dashboardShowsRealPendingWorkAndTracksStateChanges() throws Exception {
        JsonNode u=user("待办测试",null);String token=u.path("token").asText();
        long charter=postData("/api/orders",booking("CHARTER"),token,200).path("id").asLong();long ride=postData("/api/orders",booking("RIDE"),token,200).path("id").asLong();
        JsonNode before=json.readTree(mvc.perform(get("/api/admin/dashboard").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(before.path("pendingQuotes").asLong()).isEqualTo(1);assertThat(before.path("pendingPayments").asLong()).isEqualTo(1);assertThat(before.path("pendingConfirmations").asLong()).isZero();
        postData("/api/admin/orders/"+charter+"/quote",Map.of("amount",200),admin,200);postData("/api/orders/"+ride+"/pay",Map.of(),token,200);postData("/api/orders/"+ride+"/cancel",Map.of("reason","行程变更"),token,200);
        JsonNode after=json.readTree(mvc.perform(get("/api/admin/dashboard").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(after.path("pendingQuotes").asLong()).isZero();assertThat(after.path("pendingConfirmations").asLong()).isEqualTo(1);assertThat(after.path("cancelRequests").asLong()).isEqualTo(1);assertThat(after.path("pendingDispatch").asLong()).isZero();
    }
    @Test void contentRejectsMissingMediaUnsafeDestinationsAndUnsupportedDisplayRules() throws Exception {
        for(String type:List.of("IMAGE","VIDEO"))postData("/api/admin/content",Map.of("kind","AD","title","未填地址","mediaType",type),admin,400);
        for(String url:List.of("http://example.com/ad.png","https://","https://user:password@example.com/ad.png","javascript:alert(1)"))postData("/api/admin/content",Map.of("kind","BANNER","title","非法媒体","mediaType","IMAGE","mediaUrl",url),admin,400);
        for(String link:List.of("https://example.com","/pages/unknown/index","/pages/booking/index?type=BAD","/pages/index/index?url=https://example.com","/pages/agreement/index?kind=privacy&url=evil","//example.com/pages/index/index"))postData("/api/admin/content",Map.of("kind","AD","title","非法跳转","linkUrl",link),admin,400);
        postData("/api/admin/content",Map.of("kind","AD","title","过长跳过","skipSeconds",16),admin,400);
        JsonNode media=postData("/api/admin/content",Map.of("kind","AD","title","有效视频","mediaType","VIDEO","mediaUrl","https://media.example.com/ad.mp4","linkUrl","/pages/booking/index?type=CHARTER","skipSeconds",15),admin,200);
        assertThat(media.path("mediaType").asText()).isEqualTo("VIDEO");assertThat(media.path("skipSeconds").asInt()).isEqualTo(15);
        JsonNode text=postData("/api/admin/content",Map.of("kind","NOTICE","title","有效隐私说明","linkUrl","/pages/agreement/index?kind=privacy","skipSeconds",0),admin,200);assertThat(text.path("skipSeconds").asInt()).isZero();
    }
    @Test void concurrentAdminChangesPreserveAnActiveAdministrator() throws Exception {
        long second=postData("/api/admin/admins",Map.of("username","second-admin","password","SecondAdmin123!","nickname","第二管理员","role","ADMIN"),admin,200).path("id").asLong();long first=((Number)db.one("SELECT id FROM users WHERE username='test-admin'").get("id")).longValue();
        var request=new MockHttpServletRequest();request.setAttribute("user",db.one("SELECT * FROM users WHERE id=?",first));ExecutorService pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {var jobs=new ArrayList<Callable<Boolean>>();for(long target:List.of(first,second))jobs.add(()->{barrier.await();try{admins.admin(target,Map.of("enabled",false),request);return true;}catch(Api.Failure failure){assertThat(failure.status).isEqualTo(400);return false;}});var results=pool.invokeAll(jobs);assertThat(results.get(0).get()^results.get(1).get()).isTrue();assertThat(db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'")).isEqualTo(1);}
        finally{pool.shutdownNow();db.update("UPDATE users SET status='ACTIVE' WHERE username='test-admin'");}
    }
}
