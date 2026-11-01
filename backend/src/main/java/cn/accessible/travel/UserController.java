package cn.accessible.travel;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {
    final Db db;final Auth auth;final SettingsService settings;final PlatformService platform;final MapService maps;final OrderService orders;final PromotionService promotion;final WechatAuthService wechatAuth;final WechatAccountService wechatAccounts;final String miniKey;
    UserController(Db db,Auth auth,SettingsService settings,PlatformService platform,MapService maps,OrderService orders,PromotionService promotion,WechatAuthService wechatAuth,WechatAccountService wechatAccounts,@Value("${app.amap-mini-key:}")String miniKey){this.db=db;this.auth=auth;this.settings=settings;this.platform=platform;this.maps=maps;this.orders=orders;this.promotion=promotion;this.wechatAuth=wechatAuth;this.wechatAccounts=wechatAccounts;this.miniKey=miniKey;}
    @GetMapping("/health") public Api.Result health(){return Api.ok(Map.of("status","UP","mode",auth.dev()?"DEV":"PRODUCTION"));}
    @PostMapping("/auth/admin/login") public Api.Result login(@RequestBody Map<String,Object> body,HttpServletRequest request){return Api.ok(auth.adminLogin(body,request.getRemoteAddr()));}
    @PostMapping("/auth/dev-login") @Transactional public Api.Result devLogin(@RequestBody Map<String,Object> body){
        if(!auth.dev())throw Api.fail(404,"开发登录已关闭");String nickname=Inputs.str(body,"nickname",60);if(nickname.isBlank())nickname="体验用户";
        String phone=Inputs.str(body,"phone",20);if(!phone.isEmpty())Inputs.phone(body,"phone");
        String invite=Inputs.str(body,"inviteCode",20);Long referrer=null;if(!invite.isEmpty()){var ref=db.maybe("SELECT id FROM users WHERE invite_code=? AND role='USER'",invite);Api.require(ref!=null,"推广码不存在");referrer=Inputs.longValue(ref,"id");}
        // Development identities remain separate even with the same manually entered phone.
        long id=db.insert("INSERT INTO users(nickname,phone,invite_code,referrer_id) VALUES (?,?,?,?)",nickname,phone.isEmpty()?null:phone,auth.randomCode(),referrer);
        platform.message(id,"欢迎使用预约出行","当前为开发环境，手填手机号未进行运营商或微信授权校验，模拟支付不会实际扣款。");return Api.ok(auth.loginResult(db.one("SELECT * FROM users WHERE id=?",id)));
    }
    @PostMapping("/auth/wechat/login") public Api.Result wechat(@RequestBody Map<String,Object> body){
        Api.require(Inputs.bool(body,"agreementAccepted",false),"请先阅读并同意用户协议及隐私政策");
        String code=Inputs.required(body,"code",512);
        String phoneCode=Inputs.str(body,"phoneCode",512),invite=Inputs.str(body,"inviteCode",20),nickname=Inputs.str(body,"nickname",60);
        Api.require(phoneCode.isEmpty()||!phoneCode.equals(code),"手机号授权凭证与微信登录凭证不能混用，请重新授权");
        var session=wechatAuth.code2Session(code);
        var phone=phoneCode.isEmpty()?null:wechatAuth.phoneNumber(phoneCode);
        return Api.ok(wechatAccounts.login(session,phone,invite,nickname));
    }
    @PostMapping("/auth/logout") public Api.Result logout(HttpServletRequest request){String token=request.getHeader("Authorization");if(token!=null&&token.startsWith("Bearer "))db.update("DELETE FROM auth_tokens WHERE token_hash=?",Auth.hash(token.substring(7)));return Api.ok(null);}
    @GetMapping("/public/config") public Api.Result config(){var values=new LinkedHashMap<>(settings.all());values.put("amapMiniKey",miniKey);values.put("mode",auth.dev()?"DEV":"PRODUCTION");values.put("devLoginEnabled",auth.dev());values.put("wechatConfigured",wechatAuth.configured());return Api.ok(values);}
    @GetMapping("/public/vehicles") public Api.Result vehicles(){return Api.ok(db.list("SELECT * FROM vehicles WHERE enabled=TRUE ORDER BY id"));}
    @GetMapping("/public/home") public Api.Result home(){
        var values=settings.all();var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("platformName","servicePhone","notice"))result.put(key,values.get(key));
        result.put("settings",values);
        for(var entry:Map.of("banners","BANNER","ads","AD","notices","NOTICE","services","SERVICE").entrySet()){
            result.put(entry.getKey(),db.list("SELECT * FROM content WHERE enabled=TRUE AND kind=? ORDER BY sort_order,id",entry.getValue()));
        }
        result.put("vehicles",db.list("SELECT * FROM vehicles WHERE enabled=TRUE ORDER BY id"));return Api.ok(result);
    }
    @GetMapping("/map/tips") public Api.Result tips(@RequestParam String keywords,@RequestParam(required=false) String city){return Api.ok(maps.tips(keywords,city));}
    @PostMapping("/quotes/estimate") public Api.Result estimate(@RequestBody Map<String,Object> body){return Api.ok(platform.estimate(body));}
    @GetMapping("/me") public Api.Result me(HttpServletRequest request){return Api.ok(Auth.safe(Auth.user(request)));}
    @RequestMapping(value="/me",method={RequestMethod.PATCH,RequestMethod.POST}) @Transactional public Api.Result profile(@RequestBody Map<String,Object> body,HttpServletRequest request){long id=Auth.userId(request);Api.require(!body.containsKey("phone"),"手机号须通过微信授权绑定，不能手动修改");if(body.containsKey("nickname"))db.update("UPDATE users SET nickname=? WHERE id=?",Inputs.required(body,"nickname",60),id);
        return Api.ok(Auth.safe(db.one("SELECT * FROM users WHERE id=?",id)));}
    @PostMapping("/me/phone") public Api.Result bindPhone(@RequestBody Map<String,Object> body,HttpServletRequest request){
        Auth.roles(request,"USER");String openId=Objects.toString(Auth.user(request).get("openId"),"").trim();Api.require(!openId.isBlank(),"请先使用微信登录后授权手机号");
        String code=Inputs.required(body,"code",512);var phone=wechatAuth.phoneNumber(code);
        return Api.ok(wechatAccounts.bindPhone(Auth.userId(request),openId,phone));
    }
    @GetMapping("/me/passengers") public Api.Result passengers(HttpServletRequest request){return Api.ok(db.list("SELECT id,name,phone FROM passengers WHERE user_id=? ORDER BY id DESC",Auth.userId(request)));}
    @PostMapping("/me/passengers") public Api.Result passenger(@RequestBody Map<String,Object> body,HttpServletRequest request){long user=Auth.userId(request);Api.require(db.count("SELECT COUNT(*) FROM passengers WHERE user_id=?",user)<20,"最多保存20位常用乘车人");long id=db.insert("INSERT INTO passengers(user_id,name,phone) VALUES (?,?,?)",user,Inputs.required(body,"name",60),Inputs.phone(body,"phone"));return Api.ok(db.one("SELECT id,name,phone FROM passengers WHERE id=?",id));}
    @DeleteMapping("/me/passengers/{id}") public Api.Result deletePassenger(@PathVariable long id,HttpServletRequest request){if(db.update("DELETE FROM passengers WHERE id=? AND user_id=?",id,Auth.userId(request))==0)throw Api.fail(404,"乘车人不存在");return Api.ok(null);}
    @GetMapping("/me/addresses") public Api.Result addresses(HttpServletRequest request){return Api.ok(db.list("SELECT id,label,address,location FROM addresses WHERE user_id=? ORDER BY id DESC",Auth.userId(request)));}
    @PostMapping("/me/addresses") public Api.Result address(@RequestBody Map<String,Object> body,HttpServletRequest request){long user=Auth.userId(request);Api.require(db.count("SELECT COUNT(*) FROM addresses WHERE user_id=?",user)<30,"最多保存30个常用地址");String location=Inputs.required(body,"location",60);Inputs.location(location);long id=db.insert("INSERT INTO addresses(user_id,label,address,location) VALUES (?,?,?,?)",user,Inputs.required(body,"label",50),Inputs.required(body,"address",255),location);return Api.ok(db.one("SELECT id,label,address,location FROM addresses WHERE id=?",id));}
    @DeleteMapping("/me/addresses/{id}") public Api.Result deleteAddress(@PathVariable long id,HttpServletRequest request){if(db.update("DELETE FROM addresses WHERE id=? AND user_id=?",id,Auth.userId(request))==0)throw Api.fail(404,"地址不存在");return Api.ok(null);}
    @GetMapping("/orders") public Api.Result listOrders(@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String group,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){return Api.ok(orders.userPage(Auth.userId(request),status,group,page,size));}
    @PostMapping("/orders") public Api.Result create(@RequestBody Map<String,Object> body,HttpServletRequest request){Auth.roles(request,"USER");return Api.ok(orders.create(Auth.userId(request),body));}
    @GetMapping("/orders/{id}") public Api.Result order(@PathVariable long id,HttpServletRequest request){return Api.ok(orders.get(id,Auth.userId(request)));}
    @PostMapping("/orders/{id}/cancel") public Api.Result cancel(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){return Api.ok(orders.cancel(id,Auth.userId(request),body));}
    @PostMapping("/orders/{id}/accept-quote") public Api.Result accept(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){return Api.ok(orders.accept(id,Auth.userId(request),body));}
    @PostMapping("/orders/{id}/pay") public Api.Result pay(@PathVariable long id,HttpServletRequest request){return Api.ok(orders.pay(id,Auth.userId(request)));}
    @GetMapping("/me/promotion") public Api.Result promotion(HttpServletRequest request){return Api.ok(promotion.summary(Auth.userId(request)));}
    @GetMapping("/me/promotion/qrcode") public Api.Result promotionQr(HttpServletRequest request){return Api.ok(promotion.miniProgramCode(Auth.userId(request)));}
    @PostMapping("/me/withdrawals") public Api.Result withdraw(@RequestBody Map<String,Object> body,HttpServletRequest request){Auth.roles(request,"USER");return Api.ok(promotion.apply(Auth.userId(request),body));}
    @GetMapping("/me/messages") public Api.Result messages(HttpServletRequest request){return Api.ok(db.list("SELECT * FROM messages WHERE user_id=? ORDER BY id DESC LIMIT 200",Auth.userId(request)));}
    @PostMapping("/me/messages/{id}/read") public Api.Result read(@PathVariable long id,HttpServletRequest request){if(db.update("UPDATE messages SET read_flag=TRUE WHERE id=? AND user_id=?",id,Auth.userId(request))==0)throw Api.fail(404,"消息不存在");return Api.ok(null);}
}
