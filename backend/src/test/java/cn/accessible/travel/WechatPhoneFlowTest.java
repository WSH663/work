package cn.accessible.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:wechat-phone;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=phone-test-admin","app.admin-password=PhoneTestAdmin123!","app.wechat-app-id=","app.wechat-app-secret="})
@AutoConfigureMockMvc
class WechatPhoneFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Db db;
    @Autowired Auth auth;
    @Autowired WechatAccountService accounts;
    @MockitoBean WechatAuthService wechat;

    @BeforeEach void setup() {
        for (String table : List.of("order_logs","payments","commissions","withdrawals","messages","orders","passengers","addresses","auth_tokens","audit_logs")) {
            db.update("DELETE FROM " + table);
        }
        db.update("UPDATE users SET referrer_id=NULL");
        db.update("DELETE FROM users WHERE username IS NULL OR username <> 'phone-test-admin'");
        reset(wechat);
    }

    JsonNode request(MockHttpServletRequestBuilder builder, Object body, String token, int expected) throws Exception {
        if (body != null) builder.contentType("application/json").content(json.writeValueAsString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        String response = mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }

    JsonNode login(String code, String phoneCode, int expected) throws Exception {
        var body = new java.util.LinkedHashMap<String,Object>();
        body.put("code", code);
        body.put("agreementAccepted", true);
        if (phoneCode != null) body.put("phoneCode", phoneCode);
        return request(post("/api/auth/wechat/login"), body, null, expected);
    }

    long existingUser(String openId, String phone, boolean verified) {
        long id = db.insert("INSERT INTO users(open_id,nickname,phone,phone_verified,invite_code) VALUES (?,?,?,?,?)",
                openId, "原昵称", phone, verified, auth.randomCode());
        if (verified) db.update("UPDATE users SET phone_country_code='86',phone_verified_at=? WHERE id=?", LocalDateTime.of(2026,1,2,3,4), id);
        return id;
    }

    String token(long user) { return auth.loginResult(db.one("SELECT * FROM users WHERE id=?", user)).get("token").toString(); }
    Map<String,Object> phoneState(long user) { return db.one("SELECT phone,phone_verified,phone_country_code,phone_verified_at FROM users WHERE id=?", user); }

    @Test void newWechatLoginDoesNotRequirePhoneAuthorization() throws Exception {
        when(wechat.code2Session("login-only")).thenReturn(new WechatAuthService.Session("openid-login-only", ""));
        JsonNode result = login("login-only", null, 200);
        assertThat(result.path("token").asText()).isNotBlank();
        assertThat(result.path("newUser").isBoolean()).isTrue();
        assertThat(result.path("newUser").asBoolean()).isTrue();
        assertThat(result.path("user").path("phoneVerified").isBoolean()).isTrue();
        assertThat(result.path("user").path("phoneVerified").asBoolean()).isFalse();
        assertThat(result.path("user").path("phone").isNull()).isTrue();
        assertThat(result.path("user").has("openId")).isFalse();
        long id = result.path("user").path("id").asLong();
        assertThat(db.count("SELECT COUNT(*) FROM auth_tokens WHERE user_id=?", id)).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=? AND title=?", id, "欢迎使用预约出行")).isEqualTo(1);
        verify(wechat, never()).phoneNumber(anyString());
    }

    @Test void newLoginCanAtomicallySaveAuthorizedPhone() throws Exception {
        when(wechat.code2Session("login-with-phone")).thenReturn(new WechatAuthService.Session("openid-with-phone", ""));
        when(wechat.phoneNumber("phone-code")).thenReturn(new WechatAuthService.Phone("13800138000", "86"));
        JsonNode result = login("login-with-phone", "phone-code", 200);
        assertThat(result.path("newUser").asBoolean()).isTrue();
        JsonNode user = result.path("user");
        assertThat(user.path("phone").asText()).isEqualTo("13800138000");
        assertThat(user.path("phoneVerified").asBoolean()).isTrue();
        assertThat(user.path("phoneCountryCode").asText()).isEqualTo("86");
        assertThat(user.path("phoneVerifiedAt").isNull()).isFalse();
        JsonNode me = request(get("/api/me"), null, result.path("token").asText(), 200);
        assertThat(me.path("phoneVerified").isBoolean()).isTrue();
        assertThat(me.path("phoneVerified").asBoolean()).isTrue();
        assertThat(db.count("SELECT COUNT(*) FROM users WHERE open_id=?", "openid-with-phone")).isEqualTo(1);
    }

    @Test void oldLoginWithoutPhoneCodePreservesVerificationAndIdentity() throws Exception {
        long id = existingUser("openid-existing", "13900139000", true);
        var originalPhone = phoneState(id);
        when(wechat.code2Session("existing-login")).thenReturn(new WechatAuthService.Session("openid-existing", ""));
        JsonNode result = request(post("/api/auth/wechat/login"), Map.of("code", "existing-login", "agreementAccepted", true,
                "nickname", "不覆盖旧昵称", "inviteCode", "IGNORED-FOR-OLD"), null, 200);
        assertThat(result.path("newUser").isBoolean()).isTrue();
        assertThat(result.path("newUser").asBoolean()).isFalse();
        assertThat(result.path("user").path("id").asLong()).isEqualTo(id);
        assertThat(result.path("user").path("nickname").asText()).isEqualTo("原昵称");
        assertThat(result.path("user").path("phoneVerified").asBoolean()).isTrue();
        assertThat(phoneState(id)).isEqualTo(originalPhone);
        assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=?", id)).isZero();
        verify(wechat, never()).phoneNumber(anyString());
    }

    @Test void returningWechatUserIsNotNewEvenWithDefaultNickname() throws Exception {
        when(wechat.code2Session("first-or-returning")).thenReturn(new WechatAuthService.Session("openid-first-or-returning", ""));
        JsonNode first = login("first-or-returning", null, 200);
        JsonNode returning = login("first-or-returning", null, 200);
        assertThat(first.path("newUser").asBoolean()).isTrue();
        assertThat(returning.path("newUser").isBoolean()).isTrue();
        assertThat(returning.path("newUser").asBoolean()).isFalse();
        assertThat(returning.path("user").path("id")).isEqualTo(first.path("user").path("id"));
        assertThat(returning.path("user").path("nickname").asText()).isEqualTo("微信用户");
        assertThat(db.count("SELECT COUNT(*) FROM users WHERE open_id=?", "openid-first-or-returning")).isEqualTo(1);
    }

    @Test void oldLoginWithPhoneCodeUpdatesOnlyThatAccountsPhone() throws Exception {
        long id = existingUser("openid-rebind-login", "13800138000", true);
        when(wechat.code2Session("rebind-login")).thenReturn(new WechatAuthService.Session("openid-rebind-login", ""));
        when(wechat.phoneNumber("new-phone")).thenReturn(new WechatAuthService.Phone("13900139000", "86"));
        JsonNode result = login("rebind-login", "new-phone", 200);
        assertThat(result.path("user").path("id").asLong()).isEqualTo(id);
        assertThat(result.path("user").path("phone").asText()).isEqualTo("13900139000");
        assertThat(result.path("user").path("phoneVerified").asBoolean()).isTrue();
        assertThat(db.count("SELECT COUNT(*) FROM users WHERE open_id=?", "openid-rebind-login")).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=?", id)).isZero();
    }

    @Test void failedPhoneAuthorizationDuringNewLoginCreatesNoUserMessageOrToken() throws Exception {
        when(wechat.code2Session("new-failed-login")).thenReturn(new WechatAuthService.Session("openid-failed", ""));
        when(wechat.phoneNumber("expired-phone-code")).thenThrow(Api.fail(400, "微信手机号授权凭证已失效，请重新授权手机号"));
        long users = db.count("SELECT COUNT(*) FROM users");
        login("new-failed-login", "expired-phone-code", 400);
        assertThat(db.count("SELECT COUNT(*) FROM users")).isEqualTo(users);
        assertThat(db.count("SELECT COUNT(*) FROM users WHERE open_id=?", "openid-failed")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM auth_tokens")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM messages")).isZero();
    }

    @Test void failedPhoneAuthorizationDuringOldLoginPreservesPhoneAndTokens() throws Exception {
        long id = existingUser("openid-failed-existing", "13800138000", true);
        token(id);
        var original = phoneState(id);
        when(wechat.code2Session("old-failed-login")).thenReturn(new WechatAuthService.Session("openid-failed-existing", ""));
        when(wechat.phoneNumber("failed-phone")).thenThrow(Api.fail(502, "暂时无法连接微信服务，请检查网络后重新授权"));
        login("old-failed-login", "failed-phone", 502);
        assertThat(phoneState(id)).isEqualTo(original);
        assertThat(db.count("SELECT COUNT(*) FROM auth_tokens WHERE user_id=?", id)).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=?", id)).isZero();
    }

    @Test void bindPhoneRequiresAuthenticatedUserRoleAndWechatIdentityBeforeUpstreamCall() throws Exception {
        request(post("/api/me/phone"), Map.of("code", "unused"), null, 401);
        for (String role : List.of("ADMIN", "OPERATOR", "FINANCE")) {
            long id = db.insert("INSERT INTO users(open_id,nickname,role,invite_code) VALUES (?,?,?,?)",
                    "openid-role-" + role, role, role, auth.randomCode());
            request(post("/api/me/phone"), Map.of("code", "unused"), token(id), 403);
        }
        long devUser = existingUser(null, "13800138000", false);
        request(post("/api/me/phone"), Map.of("code", "unused"), token(devUser), 400);
        long wechatUser = existingUser("openid-missing-phone-code", null, false);
        request(post("/api/me/phone"), Map.of("code", " "), token(wechatUser), 400);
        verify(wechat, never()).phoneNumber(anyString());
        assertThat(phoneState(devUser)).containsEntry("phone", "13800138000").containsEntry("phoneVerified", false);
    }

    @Test void authorizedBindingReturnsFreshVerifiedProfileAndKeepsCurrentSession() throws Exception {
        long id = existingUser("openid-bind", "13800138000", false);
        String bearer = token(id);
        when(wechat.phoneNumber("bind-code")).thenReturn(new WechatAuthService.Phone("13900139000", "86"));
        JsonNode result = request(post("/api/me/phone"), Map.of("code", "bind-code"), bearer, 200);
        assertThat(result.path("id").asLong()).isEqualTo(id);
        assertThat(result.path("phone").asText()).isEqualTo("13900139000");
        assertThat(result.path("phoneVerified").asBoolean()).isTrue();
        assertThat(result.has("openId")).isFalse();
        JsonNode me = request(get("/api/me"), null, bearer, 200);
        assertThat(me.path("phone").asText()).isEqualTo("13900139000");
        assertThat(me.path("phoneVerified").asBoolean()).isTrue();
        assertThat(db.count("SELECT COUNT(*) FROM auth_tokens WHERE user_id=?", id)).isEqualTo(1);
    }

    @Test void manualPhoneUpdatesAreRejectedWithoutApplyingNicknameInDev() throws Exception {
        long id = existingUser("openid-manual", "13800138000", true);
        String bearer = token(id);
        var originalPhone = phoneState(id);
        for (var method : List.of(post("/api/me"), patch("/api/me"))) {
            request(method, Map.of("nickname", "不得保存", "phone", "13900139000"), bearer, 400);
            assertThat(db.one("SELECT nickname FROM users WHERE id=?", id)).containsEntry("nickname", "原昵称");
            assertThat(phoneState(id)).isEqualTo(originalPhone);
        }
        JsonNode updated = request(patch("/api/me"), Map.of("nickname", "只改昵称"), bearer, 200);
        assertThat(updated.path("nickname").asText()).isEqualTo("只改昵称");
        assertThat(phoneState(id)).isEqualTo(originalPhone);
        verifyNoInteractions(wechat);
    }

    @Test void matchingAuthorizedPhoneNeverMergesDevOrOtherWechatAccounts() throws Exception {
        long devId = existingUser(null, "13800138000", false);
        db.update("UPDATE users SET available_amount=21 WHERE id=?", devId);
        long passenger = db.insert("INSERT INTO passengers(user_id,name,phone) VALUES (?,?,?)", devId, "原乘车人", "13800138000");
        when(wechat.code2Session("first-login")).thenReturn(new WechatAuthService.Session("openid-first", ""));
        when(wechat.code2Session("second-login")).thenReturn(new WechatAuthService.Session("openid-second", ""));
        when(wechat.phoneNumber(anyString())).thenReturn(new WechatAuthService.Phone("13800138000", "86"));
        JsonNode first = login("first-login", "first-phone", 200);
        JsonNode second = login("second-login", "second-phone", 200);
        long firstId = first.path("user").path("id").asLong(), secondId = second.path("user").path("id").asLong();
        assertThat(List.of(devId, firstId, secondId)).doesNotHaveDuplicates();
        assertThat(db.count("SELECT COUNT(*) FROM users WHERE phone=?", "13800138000")).isEqualTo(3);
        assertThat(phoneState(devId)).containsEntry("phoneVerified", false);
        assertThat(Inputs.number(db.one("SELECT available_amount FROM users WHERE id=?", devId), "availableAmount")).isEqualByComparingTo("21");
        assertThat(db.one("SELECT user_id FROM passengers WHERE id=?", passenger)).containsEntry("userId", devId);
        assertThat(first.path("user").path("availableAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(second.path("user").path("availableAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(first.path("user").path("inviteCode").asText()).isNotEqualTo(second.path("user").path("inviteCode").asText());
    }

    @Test void failedBindingPreservesAllPreviousPhoneVerificationData() throws Exception {
        long id = existingUser("openid-bind-failure", "13800138000", true);
        var original = phoneState(id);
        when(wechat.phoneNumber("bind-failure")).thenThrow(Api.fail(403, "当前小程序尚未开通手机号授权权限，请联系平台"));
        request(post("/api/me/phone"), Map.of("code", "bind-failure"), token(id), 403);
        assertThat(phoneState(id)).isEqualTo(original);
        assertThat(db.count("SELECT COUNT(*) FROM auth_tokens WHERE user_id=?", id)).isEqualTo(1);
    }

    @Test void disablingAccountWhileBindingAuthorizationIsInFlightPreventsPhoneWrite() throws Exception {
        long id = existingUser("openid-disabled-during-bind", "13800138000", true);
        String bearer = token(id);
        var original = phoneState(id);
        when(wechat.phoneNumber("in-flight-bind")).thenAnswer(invocation -> {
            db.update("UPDATE users SET status='DISABLED' WHERE id=?", id);
            return new WechatAuthService.Phone("13900139000", "86");
        });
        request(post("/api/me/phone"), Map.of("code", "in-flight-bind"), bearer, 400);
        assertThat(phoneState(id)).isEqualTo(original);
        assertThat(db.one("SELECT status FROM users WHERE id=?", id)).containsEntry("status", "DISABLED");
        request(get("/api/me"), null, bearer, 401);
    }

    @Test void disablingAccountDuringLoginPhoneAuthorizationPreventsWriteAndNewToken() throws Exception {
        long id = existingUser("openid-disabled-during-login", "13800138000", true);
        var original = phoneState(id);
        when(wechat.code2Session("in-flight-login")).thenReturn(new WechatAuthService.Session("openid-disabled-during-login", ""));
        when(wechat.phoneNumber("in-flight-login-phone")).thenAnswer(invocation -> {
            db.update("UPDATE users SET status='DISABLED' WHERE id=?", id);
            return new WechatAuthService.Phone("13900139000", "86");
        });
        login("in-flight-login", "in-flight-login-phone", 400);
        assertThat(phoneState(id)).isEqualTo(original);
        assertThat(db.count("SELECT COUNT(*) FROM auth_tokens WHERE user_id=?", id)).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=?", id)).isZero();
    }

    @Test void concurrentFirstLoginCommitsOnlyOneIdentityAndWelcomeMessage() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2);
            Callable<Map<String,Object>> login = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return accounts.login(new WechatAuthService.Session("openid-concurrent-first", ""),
                        new WechatAuthService.Phone("13800138000", "86"), "", "并发微信用户");
            };
            var first = pool.submit(login);
            var second = pool.submit(login);
            Map<String,Object> firstResult = first.get(20, TimeUnit.SECONDS);
            Map<String,Object> secondResult = second.get(20, TimeUnit.SECONDS);
            long id = Inputs.longValue(db.one("SELECT id FROM users WHERE open_id=?", "openid-concurrent-first"), "id");
            assertThat(json.valueToTree(firstResult).path("user").path("id").asLong()).isEqualTo(id);
            assertThat(json.valueToTree(secondResult).path("user").path("id").asLong()).isEqualTo(id);
            assertThat(firstResult.get("token")).isNotEqualTo(secondResult.get("token"));
            assertThat(List.of(firstResult.get("newUser"), secondResult.get("newUser")))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(db.count("SELECT COUNT(*) FROM users WHERE open_id=?", "openid-concurrent-first")).isEqualTo(1);
            assertThat(db.count("SELECT COUNT(*) FROM messages WHERE user_id=? AND title=?", id, "欢迎使用预约出行")).isEqualTo(1);
            assertThat(db.count("SELECT COUNT(*) FROM auth_tokens WHERE user_id=?", id)).isEqualTo(2);
            assertThat(phoneState(id)).containsEntry("phone", "13800138000").containsEntry("phoneVerified", true);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void referredInternationalAuthorizedPhoneIsMaskedInPromotionRelations() throws Exception {
        long referrer = existingUser("openid-international-referrer", null, false);
        String invite = db.one("SELECT invite_code FROM users WHERE id=?", referrer).get("inviteCode").toString();
        when(wechat.code2Session("international-login")).thenReturn(new WechatAuthService.Session("openid-international-referred", ""));
        when(wechat.phoneNumber("international-phone-code")).thenReturn(new WechatAuthService.Phone("4155552671", "1"));
        JsonNode customer = request(post("/api/auth/wechat/login"), Map.of("code", "international-login", "agreementAccepted", true,
                "phoneCode", "international-phone-code", "inviteCode", invite), null, 200);
        assertThat(customer.path("user").path("phone").asText()).isEqualTo("4155552671");
        assertThat(customer.path("user").path("phoneCountryCode").asText()).isEqualTo("1");
        assertThat(customer.path("user").path("phoneVerified").asBoolean()).isTrue();
        JsonNode summary = request(get("/api/me/promotion"), null, token(referrer), 200);
        JsonNode relations = summary.path("relations");
        assertThat(relations.size()).isEqualTo(1);
        assertThat(relations.get(0).path("id").asLong()).isEqualTo(customer.path("user").path("id").asLong());
        assertThat(relations.get(0).path("phone").asText()).isEqualTo("415****2671").doesNotContain("4155552671");
        verify(wechat).phoneNumber("international-phone-code");
    }

    @Test void promotionCodeDelegatesToSharedWechatServiceWithUsersInviteScene() throws Exception {
        long id = existingUser("openid-promotion", null, false);
        String invite = db.one("SELECT invite_code FROM users WHERE id=?", id).get("inviteCode").toString();
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB), "png", encoded);
        when(wechat.configured()).thenReturn(true);
        when(wechat.miniProgramCode(any(JsonNode.class))).thenReturn(encoded.toByteArray());
        JsonNode result = request(get("/api/me/promotion/qrcode"), null, token(id), 200);
        assertThat(result.path("source").asText()).isEqualTo("WECHAT_MINIPROGRAM");
        assertThat(result.path("scene").asText()).isEqualTo(invite);
        assertThat(result.path("image").asText()).startsWith("data:image/png;base64,");
        var payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(wechat).miniProgramCode(payload.capture());
        assertThat(payload.getValue().path("scene").asText()).isEqualTo(invite);
        assertThat(payload.getValue().path("page").asText()).isEqualTo("pages/index/index");
        assertThat(payload.getValue().path("env_version").asText()).isEqualTo("release");
        assertThat(payload.getValue().path("width").asInt()).isEqualTo(430);
        assertThat(payload.getValue().path("check_path").asBoolean()).isFalse();
        verify(wechat).configured();
        verifyNoMoreInteractions(wechat);
    }

    @Test void loginRejectsConsentFailureAndMixedLoginPhoneCodesBeforeCallingWechat() throws Exception {
        request(post("/api/auth/wechat/login"), Map.of("code", "unused", "agreementAccepted", false), null, 400);
        login("same-code", "same-code", 400);
        verifyNoInteractions(wechat);
        assertThat(db.count("SELECT COUNT(*) FROM auth_tokens")).isZero();
    }
}
