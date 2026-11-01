package cn.accessible.travel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Base64;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionService {
    final Db db;final PlatformService platform;final SettingsService settings;final ObjectMapper json;final WechatAuthService wechat;
    PromotionService(Db db,PlatformService platform,SettingsService settings,ObjectMapper json,WechatAuthService wechat){this.db=db;this.platform=platform;this.settings=settings;this.json=json;this.wechat=wechat;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> summary(long user){var profile=db.one("SELECT * FROM users WHERE id=?",user);var commissions=db.list("SELECT * FROM commissions WHERE user_id=? ORDER BY id DESC",user);BigDecimal total=commissions.stream().filter(c->Set.of("PENDING","SETTLED").contains(c.get("status"))).map(c->Inputs.number(c,"amount")).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal pending=commissions.stream().filter(c->"PENDING".equals(c.get("status"))).map(c->Inputs.number(c,"amount")).reduce(BigDecimal.ZERO,BigDecimal::add);
        var relations=db.list("SELECT id,nickname,phone,created_at FROM users WHERE referrer_id=? ORDER BY id DESC",user);for(var relation:relations){Object phone=relation.get("phone");if(phone!=null){String value=phone.toString();relation.put("phone",value.length()>7?value.substring(0,3)+"****"+value.substring(value.length()-4):"****");}}
        return Map.of("inviteCode",profile.get("inviteCode"),"availableAmount",profile.get("availableAmount"),"frozenAmount",profile.get("frozenAmount"),"pendingAmount",pending,"totalCommission",total,"relations",relations,"commissions",commissions,"withdrawals",db.list("SELECT * FROM withdrawals WHERE user_id=? ORDER BY id DESC",user),"withdrawalMinimum",settings.number("withdrawalMinimum"));}
    /** Generates the official WeChat mini-program code; the app secret never leaves the server. */
    public Map<String,Object> miniProgramCode(long user){
        Api.require(wechat.configured(),"微信小程序配置尚未补齐，暂不能生成二维码");
        var profile=db.one("SELECT invite_code FROM users WHERE id=?",user);String invite=Objects.toString(profile.get("inviteCode"),"").trim();Api.require(!invite.isBlank(),"邀请码暂不可用，请刷新后重试");
        var payload=json.createObjectNode();payload.put("scene",invite);payload.put("page","pages/index/index");payload.put("check_path",false);payload.put("env_version","release");payload.put("width",430);
        byte[] body=brandCode(wechat.miniProgramCode(payload));
        return Map.of("image","data:image/png;base64,"+Base64.getEncoder().encodeToString(body),"scene",invite,"source","WECHAT_MINIPROGRAM");
    }
    /** Replaces only the official centre avatar area; never redraw the surrounding code. */
    static byte[] brandCode(byte[] source){
        try(InputStream logoStream=PromotionService.class.getResourceAsStream("/brand-logo.png")){
            if(logoStream==null)return source;
            BufferedImage code=ImageIO.read(new java.io.ByteArrayInputStream(source));
            BufferedImage logo=ImageIO.read(logoStream);
            if(code==null||logo==null)return source;
            int dimension=Math.min(code.getWidth(),code.getHeight());
            if(dimension<280)return source;
            int side=(int)(dimension*0.26),box=(int)(dimension*0.40);
            int x=(code.getWidth()-box)/2,y=(code.getHeight()-box)/2;
            BufferedImage branded=new BufferedImage(code.getWidth(),code.getHeight(),BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=branded.createGraphics();
            g.setComposite(AlphaComposite.SrcOver);g.drawImage(code,0,0,null);
            g.setClip(new java.awt.geom.Ellipse2D.Double(x,y,box,box));
            g.setColor(java.awt.Color.WHITE);g.fillOval(x,y,box,box);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int logoSide=side;int lx=(code.getWidth()-logoSide)/2,ly=(code.getHeight()-logoSide)/2;
            g.drawImage(logo,lx,ly,logoSide,logoSide,null);g.dispose();
            ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(branded,"png",output);return output.toByteArray();
        }catch(Exception ignored){return source;}
    }
    @Transactional public Map<String,Object> apply(long user,Map<String,Object> body){
        String requestKey=Inputs.str(body,"requestKey",64);if(!requestKey.isEmpty())Api.require(requestKey.matches("[A-Za-z0-9_-]{1,64}"),"提交标识格式不正确，请重新提交");
        var profile=db.one("SELECT * FROM users WHERE id=? FOR UPDATE",user);
        if(!requestKey.isEmpty()){var previous=db.maybe("SELECT * FROM withdrawals WHERE user_id=? AND request_key=?",user,requestKey);if(previous!=null)return previous;}
        BigDecimal amount=Inputs.money(body,"amount");Api.require(amount.compareTo(settings.number("withdrawalMinimum"))>=0,"未达到最低提现金额");String name=Inputs.required(body,"accountName",60),account=Inputs.required(body,"accountNo",100);Api.require(Inputs.number(profile,"availableAmount").compareTo(amount)>=0,"可提现余额不足");
        db.update("UPDATE users SET available_amount=available_amount-?,frozen_amount=frozen_amount+? WHERE id=?",amount,amount,user);long id=db.insert("INSERT INTO withdrawals(user_id,amount,account_name,account_no,request_key) VALUES (?,?,?,?,?)",user,amount,name,account,requestKey.isEmpty()?null:requestKey);platform.message(user,"提现申请已提交","提现 "+amount+" 元，等待财务审核");return db.one("SELECT * FROM withdrawals WHERE id=?",id);}
    private record LockedWithdrawal(Map<String,Object> row,Map<String,Object> profile) {}
    private LockedWithdrawal lockWithdrawal(long id){long user=Inputs.longValue(db.one("SELECT user_id FROM withdrawals WHERE id=?",id),"userId");var profile=db.one("SELECT * FROM users WHERE id=? FOR UPDATE",user);var row=db.one("SELECT * FROM withdrawals WHERE id=? FOR UPDATE",id);return new LockedWithdrawal(row,profile);}
    @Transactional public Map<String,Object> review(long id,long actor,Map<String,Object> body){var locked=lockWithdrawal(id);var row=locked.row();Api.require("PENDING".equals(row.get("status")),"该提现已处理");Api.require(body.get("approved") instanceof Boolean,"请选择通过或驳回提现申请");boolean approved=Inputs.bool(body,"approved",false);String remark=approved?Inputs.str(body,"remark",1000):Inputs.required(body,"remark",1000);long user=Inputs.longValue(row,"userId");Api.require(Inputs.number(locked.profile(),"frozenAmount").compareTo(Inputs.number(row,"amount"))>=0,"冻结余额异常，请核对财务流水");
        if(!approved)db.update("UPDATE users SET available_amount=available_amount+?,frozen_amount=frozen_amount-? WHERE id=?",row.get("amount"),row.get("amount"),user);db.update("UPDATE withdrawals SET status=?,remark=?,updated_at=? WHERE id=?",approved?"APPROVED":"REJECTED",remark,LocalDateTime.now(),id);platform.message(user,"提现审核结果",approved?"提现申请已通过，等待财务付款":"提现申请未通过，冻结金额已退回："+remark);platform.audit(actor,"WITHDRAWAL_REVIEW","提现 "+id);return db.one("SELECT * FROM withdrawals WHERE id=?",id);}
    @Transactional public Map<String,Object> paid(long id,long actor,Map<String,Object> body){var locked=lockWithdrawal(id);var row=locked.row();Api.require("APPROVED".equals(row.get("status")),"提现尚未审核通过或已付款");String reference=Inputs.required(body,"paymentReference",120);long user=Inputs.longValue(row,"userId");Api.require(Inputs.number(locked.profile(),"frozenAmount").compareTo(Inputs.number(row,"amount"))>=0,"冻结余额异常，请核对财务流水");db.update("UPDATE users SET frozen_amount=frozen_amount-? WHERE id=?",row.get("amount"),user);db.update("UPDATE withdrawals SET status='PAID',payment_reference=?,updated_at=? WHERE id=?",reference,LocalDateTime.now(),id);platform.message(user,"提现已付款","财务已登记付款 "+row.get("amount")+" 元，请核对到账；付款凭证："+reference);platform.audit(actor,"WITHDRAWAL_PAID","提现 "+id);return db.one("SELECT * FROM withdrawals WHERE id=?",id);}
    @Transactional public Map<String,Object> cancel(long id,long actor,Map<String,Object> body){String reason=Inputs.required(body,"reason",500);var locked=lockWithdrawal(id);var row=locked.row();Api.require("APPROVED".equals(row.get("status")),"仅审核通过且尚未付款的提现可撤销，请刷新后重试");long user=Inputs.longValue(row,"userId");Api.require(Inputs.number(locked.profile(),"frozenAmount").compareTo(Inputs.number(row,"amount"))>=0,"冻结余额异常，请核对财务流水");db.update("UPDATE users SET available_amount=available_amount+?,frozen_amount=frozen_amount-? WHERE id=?",row.get("amount"),row.get("amount"),user);db.update("UPDATE withdrawals SET status='REJECTED',remark=?,updated_at=? WHERE id=?","已撤销审核："+reason,LocalDateTime.now(),id);platform.message(user,"提现审核已撤销","提现 "+row.get("amount")+" 元尚未付款，审核已撤销，冻结金额已退回可提现余额："+reason);platform.audit(actor,"WITHDRAWAL_CANCEL","提现 "+id);return db.one("SELECT * FROM withdrawals WHERE id=?",id);}
}
