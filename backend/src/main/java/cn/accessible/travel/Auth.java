package cn.accessible.travel;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class Auth implements HandlerInterceptor, CommandLineRunner {
    final Db db; final Environment env; final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(11);
    final String adminUsername,adminPassword; private final SecureRandom random=new SecureRandom();
    private final Map<String,LoginAttempts> attempts=new java.util.concurrent.ConcurrentHashMap<>();
    Auth(Db db,Environment env,@Value("${app.admin-username:admin}") String username,@Value("${app.admin-password:}") String password) {this.db=db;this.env=env;this.adminUsername=username;this.adminPassword=password;}
    public boolean dev(){return Arrays.asList(env.getActiveProfiles().length==0?env.getDefaultProfiles():env.getActiveProfiles()).contains("dev")&&!Arrays.asList(env.getActiveProfiles()).contains("prod")&&!Arrays.asList(env.getActiveProfiles()).contains("production");}
    @Override public void run(String... args) {
        if(db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN'")==0){
            String password=adminPassword;
            if(password.isBlank()) throw new IllegalStateException("First startup requires an explicit ADMIN_PASSWORD");
            Api.require(password.length()>=10,"管理员初始密码至少10位");
            db.insert("INSERT INTO users(username,password_hash,nickname,role,invite_code) VALUES (?,?,?,'ADMIN',?)",adminUsername,passwords.encode(password),"平台管理员",randomCode());
        }
        if(!dev()&&Arrays.asList(env.getActiveProfiles()).contains("dev")) throw new IllegalStateException("Do not combine dev and production profiles");
        db.update("DELETE FROM auth_tokens WHERE expires_at < ?",LocalDateTime.now());
    }
    public String randomCode(){return "W"+HexFormat.of().formatHex(random.generateSeed(5)).toUpperCase(Locale.ROOT);}
    public Map<String,Object> loginResult(Map<String,Object> user) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO auth_tokens(token_hash,user_id,expires_at) VALUES (?,?,?)",hash(token),user.get("id"),LocalDateTime.now().plusDays(7));
        return Map.of("token",token,"user",safe(user),"mode",dev()?"DEV":"PRODUCTION");
    }
    public Map<String,Object> adminLogin(Map<String,Object> data,String ip){
        String username=Inputs.required(data,"username",64),password=Inputs.required(data,"password",200);String key=ip+":"+username;
        if(attempts.size()>5000)attempts.entrySet().removeIf(e->e.getValue().reset.isBefore(LocalDateTime.now()));
        LoginAttempts a=attempts.compute(key,(k,v)->v==null||v.reset.isBefore(LocalDateTime.now())?new LoginAttempts(0,LocalDateTime.now().plusMinutes(15)):v);
        if(a.count>=10)throw Api.fail(429,"登录尝试过多，请15分钟后重试");
        var user=db.maybe("SELECT * FROM users WHERE username=? AND role <> 'USER'",username);
        if(user==null||user.get("passwordHash")==null||!passwords.matches(password,user.get("passwordHash").toString())||!"ACTIVE".equals(user.get("status"))){a.count++;throw Api.fail(401,"账号或密码错误，或账号已停用");}
        attempts.remove(key);return loginResult(user);
    }
    public static Map<String,Object> safe(Map<String,Object> user){var result=new LinkedHashMap<>(user);result.remove("passwordHash");result.remove("openId");Object verified=user.get("phoneVerified");result.put("phoneVerified",Boolean.TRUE.equals(verified)||(verified instanceof Number number&&number.intValue()==1));result.put("balance",result.get("availableAmount"));result.put("enabled","ACTIVE".equals(user.get("status")));return result;}
    public static Map<String,Object> user(HttpServletRequest request){@SuppressWarnings("unchecked") var user=(Map<String,Object>)request.getAttribute("user");if(user==null)throw Api.fail(401,"请先登录");return user;}
    public static long userId(HttpServletRequest request){return Inputs.longValue(user(request),"id");}
    public static void roles(HttpServletRequest request,String... roles){if(!Arrays.asList(roles).contains(user(request).get("role")))throw Api.fail(403,"当前账号没有此操作权限");}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        if("OPTIONS".equals(request.getMethod()))return true;
        String path=request.getRequestURI();
        if(!path.startsWith("/api/"))return true;
        if(path.equals("/api/health")||path.startsWith("/api/auth/")||path.startsWith("/api/public/")||Set.of("/api/map/tips","/api/map/reverse","/api/map/nearby","/api/map/static").contains(path)||path.equals("/api/quotes/estimate"))return true;
        String header=request.getHeader("Authorization");if(header==null||!header.startsWith("Bearer "))throw Api.fail(401,"请先登录");
        var user=db.maybe("SELECT u.* FROM users u JOIN auth_tokens t ON t.user_id=u.id WHERE t.token_hash=? AND t.expires_at>?",hash(header.substring(7)),LocalDateTime.now());
        if(user==null||!"ACTIVE".equals(user.get("status")))throw Api.fail(401,"登录已失效或账号已停用，请重新登录");
        request.setAttribute("user",user);
        if(path.startsWith("/api/admin/")) roles(request,"ADMIN","OPERATOR","FINANCE");
        return true;
    }
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static class LoginAttempts{int count;final LocalDateTime reset;LoginAttempts(int count,LocalDateTime reset){this.count=count;this.reset=reset;}}
}

@Component
class WebConfig implements WebMvcConfigurer {
    final Auth auth;final String origins;
    WebConfig(Auth auth,@Value("${app.cors-origins}")String origins){this.auth=auth;this.origins=origins;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(auth).addPathPatterns("/api/**");}
    @Override public void addCorsMappings(CorsRegistry registry){registry.addMapping("/api/**").allowedOrigins(origins.split(",")).allowedMethods("GET","POST","PATCH","DELETE","OPTIONS").allowedHeaders("Authorization","Content-Type").maxAge(3600);}
}
