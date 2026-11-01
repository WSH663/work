package cn.accessible.travel;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits verified WeChat identities and phone bindings atomically, after upstream authorization. */
@Service
public class WechatAccountService {
    final Db db;
    final Auth auth;
    final PlatformService platform;
    final TransactionTemplate transactions;

    WechatAccountService(Db db, Auth auth, PlatformService platform, PlatformTransactionManager manager) {
        this.db = db;
        this.auth = auth;
        this.platform = platform;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Map<String,Object> login(WechatAuthService.Session session, WechatAuthService.Phone phone, String invite, String nickname) {
        // The unique open_id index arbitrates concurrent first logins. Retry in a fresh transaction.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactions.execute(transaction -> {
                    var user = db.maybe("SELECT * FROM users WHERE open_id=? FOR UPDATE", session.openId());
                    boolean newUser = user == null;
                    if (newUser) {
                        Long referrer = null;
                        if (!invite.isEmpty()) {
                            var ref = db.maybe("SELECT id FROM users WHERE invite_code=? AND role='USER' AND status='ACTIVE'", invite);
                            Api.require(ref != null, "推广码不存在或已失效");
                            referrer = Inputs.longValue(ref, "id");
                        }
                        long id = db.insert("INSERT INTO users(open_id,nickname,invite_code,referrer_id) VALUES (?,?,?,?)",
                                session.openId(), nickname.isBlank() ? "微信用户" : nickname, auth.randomCode(), referrer);
                        user = db.one("SELECT * FROM users WHERE id=?", id);
                        platform.message(id, "欢迎使用预约出行", "微信账号已登录，可在个人中心完善资料并预约服务。");
                    }
                    Api.require("USER".equals(user.get("role")) && "ACTIVE".equals(user.get("status")), "账号已停用或不可用于微信登录，请联系客服");
                    if (phone != null) user = savePhone(Inputs.longValue(user, "id"), phone);
                    var result = new LinkedHashMap<>(auth.loginResult(user));
                    result.put("newUser", newUser);
                    return result;
                });
            } catch (DuplicateKeyException conflict) {
                if (attempt == 2) throw Api.fail(409, "登录正在处理中，请重新点击微信登录");
            }
        }
        throw Api.fail(409, "登录正在处理中，请重新点击微信登录");
    }

    public Map<String,Object> bindPhone(long userId, String expectedOpenId, WechatAuthService.Phone phone) {
        return transactions.execute(transaction -> {
            var user = db.one("SELECT * FROM users WHERE id=? FOR UPDATE", userId);
            Api.require("USER".equals(user.get("role")) && "ACTIVE".equals(user.get("status")), "账号已停用或不可绑定手机号，请重新登录");
            Api.require(!expectedOpenId.isBlank() && expectedOpenId.equals(user.get("openId")), "请先使用微信登录后授权手机号");
            return Auth.safe(savePhone(userId, phone));
        });
    }

    private Map<String,Object> savePhone(long userId, WechatAuthService.Phone phone) {
        // A phone number is contact information, never an account identity or merge key.
        db.update("UPDATE users SET phone=?,phone_verified=TRUE,phone_country_code=?,phone_verified_at=? WHERE id=?",
                phone.number(), phone.countryCode(), LocalDateTime.now(), userId);
        return db.one("SELECT * FROM users WHERE id=?", userId);
    }
}
