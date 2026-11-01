package cn.accessible.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-only WeChat APIs. Phone authorization and promotion codes share the stable-token cache. */
@Component
public class WechatAuthService {
    private static final Set<Integer> INVALID_TOKEN = Set.of(40001, 40014, 42001);
    private final ObjectMapper json;
    private final HttpClient http;
    private final String appId;
    private final String appSecret;
    private final Clock clock;
    private final Object tokenLock = new Object();
    private volatile CachedToken cachedToken;

    @Autowired
    WechatAuthService(ObjectMapper json, @Value("${app.wechat-app-id:}") String appId,
                      @Value("${app.wechat-app-secret:}") String appSecret) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(), appId, appSecret);
    }

    WechatAuthService(ObjectMapper json, HttpClient http, String appId, String appSecret) {
        this(json, http, appId, appSecret, Clock.systemUTC());
    }

    WechatAuthService(ObjectMapper json, HttpClient http, String appId, String appSecret, Clock clock) {
        this.json = json;
        this.http = http;
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.clock = clock;
    }

    public boolean configured() { return !appId.isBlank() && !appSecret.isBlank(); }

    private void requireConfigured() {
        if (!configured()) throw Api.fail(503, "微信服务尚未配置，请联系平台");
    }

    public Session code2Session(String code) {
        requireConfigured();
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(appId)
                + "&secret=" + encode(appSecret) + "&js_code=" + encode(code) + "&grant_type=authorization_code";
        var result = readJson(send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build()).body());
        checkError(result.path("errcode").asInt(0), "登录");
        String openId = result.path("openid").asText("").trim();
        if (openId.isBlank()) throw Api.fail(502, "微信登录未返回有效身份，请重新登录");
        return new Session(openId, result.path("unionid").asText("").trim());
    }

    /** This code comes from getPhoneNumber, never from wx.login. Never retry a network timeout. */
    public Phone phoneNumber(String code) {
        requireConfigured();
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            var result = readJson(send(post("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=" + encode(token), Map.of("code", code))).body());
            int error = result.path("errcode").asInt(0);
            if (INVALID_TOKEN.contains(error)) { invalidate(token); if (attempt == 0) continue; }
            checkError(error, "手机号授权");
            var info = result.path("phone_info");
            String number = info.path("purePhoneNumber").asText("").trim();
            String country = info.path("countryCode").asText("").trim();
            if (!appId.equals(info.path("watermark").path("appid").asText("").trim())
                    || !country.matches("[1-9]\\d{0,3}") || !number.matches("\\d{5,15}")
                    || ("86".equals(country) && !number.matches("1[3-9]\\d{9}"))) {
                throw Api.fail(502, "微信手机号授权返回信息无效，请重新授权");
            }
            return new Phone(number, country);
        }
        throw Api.fail(503, "微信手机号授权服务暂时不可用，请稍后重试");
    }

    /** The promotion service uses the same token provider, without refreshing another token independently. */
    public byte[] miniProgramCode(JsonNode payload) {
        requireConfigured();
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            var response = send(post("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=" + encode(token), payload));
            byte[] body = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (contentType.startsWith("image/") && body.length >= 64) return body;
            var result = readJson(body);
            int error = result.path("errcode").asInt(0);
            if (INVALID_TOKEN.contains(error)) { invalidate(token); if (attempt == 0) continue; }
            checkError(error, "小程序码");
            throw Api.fail(502, "微信小程序码返回信息无效，请稍后重试");
        }
        throw Api.fail(503, "微信小程序码服务暂时不可用，请稍后重试");
    }

    private String accessToken() {
        synchronized (tokenLock) {
            var current = cachedToken;
            if (current != null && clock.instant().isBefore(current.refreshAt())) return current.value();
            var result = readJson(send(post("https://api.weixin.qq.com/cgi-bin/stable_token", Map.of(
                    "grant_type", "client_credential", "appid", appId, "secret", appSecret, "force_refresh", false))).body());
            checkError(result.path("errcode").asInt(0), "服务凭证");
            String value = result.path("access_token").asText("").trim();
            long expires = result.path("expires_in").asLong(0);
            if (value.isBlank() || expires <= 0 || expires > 86400) throw Api.fail(502, "微信服务凭证返回异常，请稍后重试");
            long safety = Math.min(120, Math.max(1, expires / 10));
            cachedToken = new CachedToken(value, clock.instant().plusSeconds(Math.max(1, expires - safety)));
            return value;
        }
    }

    private void invalidate(String rejectedToken) {
        synchronized (tokenLock) {
            if (cachedToken != null && cachedToken.value().equals(rejectedToken)) cachedToken = null;
        }
    }

    private HttpRequest post(String url, Object payload) {
        try {
            return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
        } catch (Exception failure) {
            throw Api.fail(502, "微信请求暂时无法处理，请稍后重试");
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw Api.fail(502, "微信服务暂时不可用，请稍后重试");
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw Api.fail(502, "微信请求已中断，请重新授权或稍后重试");
        } catch (Api.Failure failure) {
            throw failure;
        } catch (Exception failure) {
            // Never include the exception, request URL, response, or authorization code in logs/errors.
            throw Api.fail(502, "暂时无法连接微信服务，请检查网络后重新授权");
        }
    }

    private JsonNode readJson(byte[] body) {
        try {
            JsonNode result = json.readTree(body);
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            return result;
        } catch (Exception failure) {
            throw Api.fail(502, "微信服务响应无效，请稍后重试");
        }
    }

    private void checkError(int error, String operation) {
        if (error == 0) return;
        if (error == 40029 || error == 40163) throw Api.fail(400, "微信" + operation + "凭证已失效，请重新" + ("登录".equals(operation) ? "点击微信登录" : "授权手机号"));
        if (error == 48001 || error == 48002) throw Api.fail(403, "当前小程序尚未开通" + operation + "权限，请联系平台");
        if (error == 40013 || error == 40125 || error == 40164 || error == 41002 || error == 41004)
            throw Api.fail(503, "微信服务配置暂不可用，请联系平台核对配置");
        if (INVALID_TOKEN.contains(error)) throw Api.fail(503, "微信服务凭证暂时不可用，请稍后重试");
        if (error == 45009 || error == 45011) throw Api.fail(503, "微信" + operation + "服务额度或频率受限，请稍后重试");
        throw Api.fail(502, "微信" + operation + "服务暂时不可用，请稍后重试");
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record CachedToken(String value, Instant refreshAt) {}
    public record Session(String openId, String unionId) {}
    public record Phone(String number, String countryCode) {}
}
