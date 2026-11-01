package cn.accessible.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WechatAuthServiceTest {
    final ObjectMapper json = new ObjectMapper();
    static final String TOKEN = "test-server-token";
    static final String SECRET = "test-server-secret";
    static final String PHONE = "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"+8613800138000\",\"purePhoneNumber\":\"13800138000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"test-app\"}}}";
    @SuppressWarnings("unchecked") HttpResponse<byte[]> reply(int status, String type, byte[] body) {
        var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(type)), (a,b) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }
    HttpResponse<byte[]> reply(String body) { return reply(200, "application/json", body.getBytes(StandardCharsets.UTF_8)); }
    HttpResponse<byte[]> token(String value, int expires) { return reply("{\"access_token\":\"" + value + "\",\"expires_in\":" + expires + "}"); }
    WechatAuthService service(HttpClient http) { return new WechatAuthService(json, http, "test-app", SECRET); }
    String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer value) { byte[] part = new byte[value.remaining()]; value.get(part); bytes.writeBytes(part); }
            public void onError(Throwable failure) { result.completeExceptionally(failure); }
            public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(2, TimeUnit.SECONDS);
    }
    void safeFailure(Runnable action, int expected, String text) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(Api.Failure.class, failure -> {
            assertThat(failure.status).isEqualTo(expected);
            assertThat(failure.getMessage()).contains(text).doesNotContain(SECRET, TOKEN, "private-phone-code", "upstream-private-detail");
        });
    }

    @Test void loginCodeAndPhoneCodeUseDifferentEndpointsAndServerNumbersOnly() throws Exception {
        var http = mock(HttpClient.class);
        doReturn(reply("{\"openid\":\"test-open-id\",\"unionid\":\"test-union\"}"), token(TOKEN,7200), reply(PHONE)).when(http).send(any(), any());
        var service = service(http);
        assertThat(service.code2Session("private-login-code").openId()).isEqualTo("test-open-id");
        assertThat(service.phoneNumber("private-phone-code")).isEqualTo(new WechatAuthService.Phone("13800138000","86"));
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(3)).send(requests.capture(),any());
        assertThat(requests.getAllValues().get(0).uri().getPath()).isEqualTo("/sns/jscode2session");
        assertThat(requests.getAllValues().get(0).uri().getQuery()).contains("js_code=private-login-code");
        assertThat(requests.getAllValues().get(1).method()).isEqualTo("POST");
        assertThat(requests.getAllValues().get(1).uri().getPath()).isEqualTo("/cgi-bin/stable_token");
        assertThat(json.readTree(body(requests.getAllValues().get(1))).path("force_refresh").asBoolean(true)).isFalse();
        assertThat(json.readTree(body(requests.getAllValues().get(2))).path("code").asText()).isEqualTo("private-phone-code");
    }

    @Test void phoneAndPromotionCodeShareTheCachedStableToken() throws Exception {
        var http = mock(HttpClient.class);
        byte[] image = new byte[100];
        doReturn(token(TOKEN,7200), reply(PHONE), reply(200,"image/png",image), reply(PHONE)).when(http).send(any(),any());
        var service = service(http);
        service.phoneNumber("phone-one");
        assertThat(service.miniProgramCode(json.createObjectNode().put("scene","invite"))).isEqualTo(image);
        service.phoneNumber("phone-two");
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(4)).send(requests.capture(),any());
        assertThat(requests.getAllValues().stream().filter(request -> request.uri().getPath().equals("/cgi-bin/stable_token")).count()).isEqualTo(1);
        assertThat(requests.getAllValues().stream().anyMatch(request -> request.uri().getPath().equals("/cgi-bin/token"))).isFalse();
    }

    @Test void expiresInControlsRefreshAndConcurrentColdRequestsFetchOneToken() throws Exception {
        var http = mock(HttpClient.class);
        var tokens = new AtomicInteger();
        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            if (request.uri().getPath().equals("/cgi-bin/stable_token")) { tokens.incrementAndGet(); return token(TOKEN,100); }
            return reply(PHONE);
        }).when(http).send(any(),any());
        var clock = new MutableClock();
        var service = new WechatAuthService(json,http,"test-app",SECRET,clock);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<Callable<WechatAuthService.Phone>>();
            for (int i=0;i<6;i++) tasks.add(() -> service.phoneNumber("phone-code"));
            for (var future : pool.invokeAll(tasks)) assertThat(future.get().number()).isEqualTo("13800138000");
            assertThat(tokens).hasValue(1);
            clock.now = clock.now.plusSeconds(89); service.phoneNumber("later"); assertThat(tokens).hasValue(1);
            clock.now = clock.now.plusSeconds(2); service.phoneNumber("expired"); assertThat(tokens).hasValue(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void invalidAccessTokenIsRefetchedAndRetriedOnlyOnce() throws Exception {
        var http = mock(HttpClient.class);
        doReturn(token("old-token",7200),reply("{\"errcode\":42001}"),token(TOKEN,7200),reply(PHONE)).when(http).send(any(),any());
        assertThat(service(http).phoneNumber("private-phone-code").number()).isEqualTo("13800138000");
        verify(http,times(4)).send(any(),any());
        var alwaysInvalid = mock(HttpClient.class);
        doReturn(token("old-token",7200),reply("{\"errcode\":40001}"),token(TOKEN,7200),reply("{\"errcode\":40014}")).when(alwaysInvalid).send(any(),any());
        var invalidService = service(alwaysInvalid);
        safeFailure(() -> invalidService.phoneNumber("private-phone-code"),503,"凭证");
        verify(alwaysInvalid,times(4)).send(any(),any());
        doReturn(token("fresh-token",7200),reply(PHONE)).when(alwaysInvalid).send(any(),any());
        assertThat(invalidService.phoneNumber("new-authorization").number()).isEqualTo("13800138000");
        verify(alwaysInvalid,times(6)).send(any(),any());
    }

    @Test void errorsDistinguishExpiredAuthorizationPermissionConfigurationAndQuotaWithoutLeakingDetails() throws Exception {
        for (var entry : Map.of(40029,400,40163,400,48001,403,40125,503,45009,503).entrySet()) {
            var http = mock(HttpClient.class);
            doReturn(token(TOKEN,7200),reply("{\"errcode\":"+entry.getKey()+",\"errmsg\":\"upstream-private-detail\"}")).when(http).send(any(),any());
            String text = entry.getValue()==400 ? "已失效" : entry.getValue()==403 ? "未开通" : entry.getKey()==45009 ? "额度" : "配置";
            safeFailure(() -> service(http).phoneNumber("private-phone-code"),entry.getValue(),text);
        }
    }

    @Test void malformedPhonePayloadsAndWrongAppWatermarksAreRejected() throws Exception {
        for (String payload : List.of("[]","null","broken-json","{}",PHONE.replace("test-app","other-app"),PHONE.replace("13800138000","123"),PHONE.replace("\"86\"","\"bad\""))) {
            var http = mock(HttpClient.class);
            doReturn(token(TOKEN,7200),reply(payload)).when(http).send(any(),any());
            safeFailure(() -> service(http).phoneNumber("private-phone-code"),502,"微信");
        }
    }

    @Test void failedTokenFetchIsNotCachedAndNetworkTimeoutDoesNotReplayAuthorization() throws Exception {
        var http = mock(HttpClient.class);
        doReturn(reply("{\"errcode\":-1}"), token(TOKEN,7200), reply(PHONE)).when(http).send(any(),any());
        var service = service(http);
        safeFailure(() -> service.phoneNumber("private-phone-code"),502,"微信");
        assertThat(service.phoneNumber("new-phone-code").number()).isEqualTo("13800138000");
        verify(http,times(3)).send(any(),any());
        var timeout = mock(HttpClient.class);
        doReturn(token(TOKEN,7200)).doThrow(new HttpTimeoutException("upstream-private-detail")).when(timeout).send(any(),any());
        safeFailure(() -> service(timeout).phoneNumber("private-phone-code"),502,"连接微信");
        verify(timeout,times(2)).send(any(),any());
        var badStatus = mock(HttpClient.class);
        doReturn(reply(503,"text/plain","upstream-private-detail".getBytes(StandardCharsets.UTF_8))).when(badStatus).send(any(),any());
        safeFailure(() -> service(badStatus).code2Session("private-login-code"),502,"微信服务");
    }

    @Test void interruptionIsPreservedAndMissingConfigurationNeverCallsWeChat() throws Exception {
        var http = mock(HttpClient.class);
        doThrow(new InterruptedException()).when(http).send(any(),any());
        try { safeFailure(() -> service(http).phoneNumber("private-phone-code"),502,"中断"); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        var unused = mock(HttpClient.class);
        safeFailure(() -> new WechatAuthService(json,unused,"","").phoneNumber("private-phone-code"),503,"未配置");
        verifyNoInteractions(unused);
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
