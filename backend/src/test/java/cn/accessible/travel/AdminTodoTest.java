package cn.accessible.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:admin-todos;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=todo-admin","app.admin-password=TodoAdmin123!","app.dev-payments-enabled=true"})
@AutoConfigureMockMvc
class AdminTodoTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Db db;
    @Autowired Auth auth;
    String admin, operator, finance, customer;
    long userId;

    @BeforeEach void setup() {
        for (String table : List.of("order_logs","payments","commissions","withdrawals","messages","orders","passengers","addresses","auth_tokens","audit_logs")) db.update("DELETE FROM " + table);
        db.update("UPDATE users SET referrer_id=NULL");
        db.update("DELETE FROM users WHERE username IS NULL OR username<>'todo-admin'");
        admin=token(db.one("SELECT * FROM users WHERE username='todo-admin'"));
        operator=createAccount("OPERATOR"); finance=createAccount("FINANCE"); customer=createAccount("USER");
        userId=((Number)db.one("SELECT id FROM users WHERE nickname='USER'").get("id")).longValue();
    }

    String createAccount(String role) {
        long id=db.insert("INSERT INTO users(nickname,role,invite_code) VALUES (?,?,?)",role,role,auth.randomCode());
        return token(db.one("SELECT * FROM users WHERE id=?",id));
    }
    String token(Map<String,Object> user) { return auth.loginResult(user).get("token").toString(); }
    long order(String state) { return order(state,"CHARTER",LocalDateTime.now().plusDays(1)); }
    long order(String state,String type,LocalDateTime appointment) {
        return db.insert("INSERT INTO orders(order_no,user_id,vehicle_id,vehicle_name,type,status,origin_name,destination_name,origin,destination,appointment_at,passenger_name,passenger_phone,amount,paid_amount) VALUES (?,?,1,'测试车型',?,?,'出发地','目的地','120.0,30.0','120.1,30.1',?,'乘车人','13800138000',100,100)","TODO-"+UUID.randomUUID().toString().replace("-",""),userId,type,state,appointment);
    }
    long withdrawal(String state) {
        if (Set.of("PENDING","APPROVED").contains(state)) db.update("UPDATE users SET frozen_amount=frozen_amount+10 WHERE id=?",userId);
        return db.insert("INSERT INTO withdrawals(user_id,amount,account_name,account_no,status) VALUES (?,10,'收款人','test-account',?)",userId,state);
    }
    JsonNode getData(String path,String token,int expected) throws Exception {
        var request=get(path);if(token!=null)request.header("Authorization","Bearer "+token);
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    void postData(String path,Object body,String token) throws Exception {
        mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk());
    }
    void metric(JsonNode data,String key,long expected) { assertThat(data.path(key).asLong()).as(key).isEqualTo(expected); }

    @Test void countsOnlyRealStaffTasksAndOmitsUnauthorizedGroups() throws Exception {
        order("PENDING_QUOTE");order("PENDING_QUOTE");order("PENDING_DISPATCH");order("CANCEL_REQUESTED");order("REFUNDING");
        order("PENDING_QUOTE","CHARTER",LocalDateTime.now().minusDays(1));
        order("PENDING_QUOTE","RIDE",LocalDateTime.now().plusDays(1));
        for (String state : List.of("PENDING_CONFIRM","PENDING_PAYMENT","DISPATCHED","IN_SERVICE","COMPLETED","CANCELLED","REFUNDED")) order(state);
        withdrawal("PENDING");withdrawal("APPROVED");withdrawal("PAID");withdrawal("REJECTED");
        JsonNode all=getData("/api/admin/todos",admin,200);
        metric(all,"total",7);metric(all.path("operations"),"total",4);
        metric(all.path("operations"),"pendingQuotes",2);metric(all.path("operations"),"pendingDispatch",1);metric(all.path("operations"),"cancelRequests",1);
        metric(all.path("finance"),"total",3);metric(all.path("finance"),"pendingRefunds",1);metric(all.path("finance"),"pendingWithdrawals",1);metric(all.path("finance"),"approvedWithdrawals",1);
        assertThat(all.fieldNames()).toIterable().containsExactlyInAnyOrder("operations","finance","total");
        JsonNode ops=getData("/api/admin/todos",operator,200);metric(ops,"total",4);assertThat(ops.has("finance")).isFalse();assertThat(ops.path("operations")).isEqualTo(all.path("operations"));
        JsonNode money=getData("/api/admin/todos",finance,200);metric(money,"total",3);assertThat(money.has("operations")).isFalse();assertThat(money.path("finance")).isEqualTo(all.path("finance"));
    }

    @Test void emptyCountsAreZerosAndLoginAndRolesAreRequired() throws Exception {
        JsonNode all=getData("/api/admin/todos",admin,200);metric(all,"total",0);metric(all.path("operations"),"total",0);metric(all.path("finance"),"total",0);
        getData("/api/admin/todos",null,401);getData("/api/admin/todos","invalid-token",401);getData("/api/admin/todos",customer,403);
        getData("/api/admin/orders?worklist=operations",finance,403);getData("/api/admin/orders?worklist=refunds",operator,403);getData("/api/admin/withdrawals?worklist=todo",operator,403);
        getData("/api/admin/orders?worklist=unknown",admin,400);getData("/api/admin/withdrawals?worklist=unknown",admin,400);
    }

    @Test void orderWorklistsMatchCountsAndKeepExistingFiltersAndPagination() throws Exception {
        long quote=order("PENDING_QUOTE"),dispatch=order("PENDING_DISPATCH"),cancel=order("CANCEL_REQUESTED"),refund=order("REFUNDING");
        order("PENDING_QUOTE","CHARTER",LocalDateTime.now().minusDays(1));order("PENDING_CONFIRM");order("PENDING_PAYMENT");order("COMPLETED");
        JsonNode tasks=getData("/api/admin/orders?worklist=operations",operator,200);metric(tasks,"total",3);
        assertThat(tasks.path("items").findValuesAsText("id")).containsExactlyInAnyOrder(Long.toString(quote),Long.toString(dispatch),Long.toString(cancel));
        JsonNode refunds=getData("/api/admin/orders?worklist=refunds",finance,200);metric(refunds,"total",1);metric(refunds.path("items").get(0),"id",refund);
        metric(getData("/api/admin/orders?worklist=operations&status=COMPLETED",operator,200),"total",0);
        metric(getData("/api/admin/orders?worklist=operations&status=PENDING_QUOTE",operator,200),"total",1);
        metric(getData("/api/admin/orders?worklist=refunds&status=COMPLETED",finance,200),"total",0);
        metric(getData("/api/admin/orders?status=COMPLETED",operator,200),"total",1);
        JsonNode page=getData("/api/admin/orders?worklist=operations&page=2&size=1&type=CHARTER&keyword=乘车人",operator,200);metric(page,"total",3);metric(page,"page",2);assertThat(page.path("items")).hasSize(1);
        metric(getData("/api/admin/orders",finance,200),"total",8);
    }

    @Test void withdrawalWorklistIncludesReviewAndPayoutAndIntersectsStatus() throws Exception {
        withdrawal("PENDING");withdrawal("APPROVED");withdrawal("PAID");withdrawal("REJECTED");
        metric(getData("/api/admin/withdrawals?worklist=todo",finance,200),"total",2);
        metric(getData("/api/admin/withdrawals?worklist=todo&status=PENDING",finance,200),"total",1);
        metric(getData("/api/admin/withdrawals?worklist=todo&status=APPROVED",finance,200),"total",1);
        metric(getData("/api/admin/withdrawals?worklist=todo&status=PAID",finance,200),"total",0);
        metric(getData("/api/admin/withdrawals?status=PAID",finance,200),"total",1);
        metric(getData("/api/admin/withdrawals",finance,200),"total",4);
    }

    @Test void completingOrderTasksUpdatesCountsAndCancellationMovesBetweenRoles() throws Exception {
        long quote=order("PENDING_QUOTE"),dispatch=order("PENDING_DISPATCH"),cancel=order("CANCEL_REQUESTED");
        metric(getData("/api/admin/todos",admin,200),"total",3);
        postData("/api/admin/orders/"+quote+"/quote",Map.of("amount",120),operator);
        metric(getData("/api/admin/todos",operator,200).path("operations"),"pendingQuotes",0);
        postData("/api/admin/orders/"+dispatch+"/dispatch",Map.of("driverName","司机","driverPhone","13900139000","plateNumber","示例A12345"),operator);
        metric(getData("/api/admin/todos",operator,200),"total",1);
        postData("/api/admin/orders/"+cancel+"/cancel-review",Map.of("approved",true),operator);
        metric(getData("/api/admin/todos",operator,200),"total",0);metric(getData("/api/admin/todos",finance,200).path("finance"),"pendingRefunds",1);metric(getData("/api/admin/todos",admin,200),"total",1);
        postData("/api/admin/orders/"+cancel+"/refund",Map.of("reason","取消退款"),finance);
        metric(getData("/api/admin/todos",admin,200),"total",0);
    }

    @Test void withdrawalReviewPreservesOneTaskUntilPaidOrRejected() throws Exception {
        long approved=withdrawal("PENDING"),rejected=withdrawal("PENDING");
        metric(getData("/api/admin/todos",finance,200),"total",2);
        postData("/api/admin/withdrawals/"+approved+"/review",Map.of("approved",true),finance);
        JsonNode afterReview=getData("/api/admin/todos",finance,200);metric(afterReview,"total",2);metric(afterReview.path("finance"),"pendingWithdrawals",1);metric(afterReview.path("finance"),"approvedWithdrawals",1);
        postData("/api/admin/withdrawals/"+rejected+"/review",Map.of("approved",false,"remark","收款信息错误"),finance);metric(getData("/api/admin/todos",finance,200),"total",1);
        postData("/api/admin/withdrawals/"+approved+"/paid",Map.of("paymentReference","TODO-PAY-001"),finance);metric(getData("/api/admin/todos",finance,200),"total",0);
    }
}
