package cn.accessible.travel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    final Db db;final PlatformService platform;final SettingsService settings;final Auth auth;final OrderWorkflow workflow;final CommissionService commissions;final boolean paymentsEnabled;
    OrderService(Db db,PlatformService platform,SettingsService settings,Auth auth,OrderWorkflow workflow,CommissionService commissions,@Value("${app.dev-payments-enabled:true}") boolean paymentsEnabled){this.db=db;this.platform=platform;this.settings=settings;this.auth=auth;this.workflow=workflow;this.commissions=commissions;this.paymentsEnabled=paymentsEnabled;}
    Map<String,Object> locked(long id){return db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",id);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> get(long id,Long owner){var row=db.one("SELECT * FROM orders WHERE id=?",id);if(owner!=null&&Inputs.longValue(row,"userId")!=owner)throw Api.fail(404,"订单不存在");row.put("logs",db.list("SELECT id,action,content,created_at FROM order_logs WHERE order_id=? ORDER BY id",id));row.put("refunds",db.list("SELECT * FROM payments WHERE order_id=? AND type='REFUND' ORDER BY id DESC",id));row.put("payments",db.list("SELECT * FROM payments WHERE order_id=? ORDER BY id DESC",id));return workflow.present(row);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(String where,List<Object> args,int page,int size){var result=db.page("orders",where,args,page,size);@SuppressWarnings("unchecked")var rows=(List<Map<String,Object>>)result.get("items");workflow.present(rows);return result;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> userPage(long user,String status,String group,int page,int size){
        var args=new ArrayList<Object>();args.add(user);String where=" WHERE user_id=?";
        if(!status.isBlank()){where+=" AND status=?";args.add(status);}
        else if(!group.isBlank()){
            var groups=Map.of("awaitingConfirmation",List.of("PENDING_QUOTE","PENDING_CONFIRM"),"awaitingPayment",List.of("PENDING_PAYMENT"),"upcoming",List.of("PENDING_DISPATCH","DISPATCHED"),"inService",List.of("IN_SERVICE"),"completed",List.of("COMPLETED"),"cancellation",List.of("CANCEL_REQUESTED","CANCELLED","REFUNDING","REFUNDED"));
            var states=groups.get(group);Api.require(states!=null,"订单分组不正确");where+=" AND status IN ("+String.join(",",Collections.nCopies(states.size(),"?"))+")";args.addAll(states);
        }
        return page(where,args,page,size);
    }
    private void owner(Map<String,Object> row,long owner){if(Inputs.longValue(row,"userId")!=owner)throw Api.fail(404,"订单不存在");}
    private void change(long id,long actor,String status,String action,String text){
        db.update("UPDATE orders SET status=?,updated_at=? WHERE id=?",status,LocalDateTime.now(),id);
        db.insert("INSERT INTO order_logs(order_id,actor_id,action,content) VALUES (?,?,?,?)",id,actor,action,text);
        var order=db.one("SELECT user_id,order_no FROM orders WHERE id=?",id);platform.message(Inputs.longValue(order,"userId"),"订单进度更新",order.get("orderNo")+"："+text,id);platform.audit(actor,action,"订单 "+order.get("orderNo"));
    }
    @Transactional public Map<String,Object> create(long user,Map<String,Object> body){
        String requestKey=Inputs.str(body,"requestKey",64);if(!requestKey.isEmpty())Api.require(requestKey.matches("[A-Za-z0-9_-]{1,64}"),"提交标识格式不正确，请重新提交");
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",user);
        if(!requestKey.isEmpty()){var previous=db.maybe("SELECT id FROM orders WHERE user_id=? AND request_key=?",user,requestKey);if(previous!=null)return get(Inputs.longValue(previous,"id"),user);}
        String type=Inputs.required(body,"type",20);Api.require(Set.of("RIDE","CHARTER").contains(type),"订单类型不正确");
        long carId=Inputs.id(body,"vehicleId");var car=platform.vehicle(carId);String origin=Inputs.required(body,"origin",60),destination=Inputs.required(body,"destination",60);Inputs.location(origin);Inputs.location(destination);
        String originName=Inputs.required(body,"originName",255),destinationName=Inputs.required(body,"destinationName",255),passenger=Inputs.required(body,"passengerName",60),phone=Inputs.phone(body,"passengerPhone");
        LocalDateTime appointment=Inputs.time(body,"appointmentAt"),now=LocalDateTime.now();var config=settings.all();
        Api.require(!appointment.isBefore(now.plusMinutes(Inputs.integer(config,"advanceMinutes",60,0,10080))),"请按平台提前预约规则选择时间");Api.require(!appointment.isAfter(now.plusDays(Inputs.integer(config,"maxAdvanceDays",30,1,365))),"预约时间超出可预约范围");
        BigDecimal amount=BigDecimal.ZERO,distance=BigDecimal.ZERO,hours=null;String status;
        if("RIDE".equals(type)){var estimate=platform.estimate(body);amount=Inputs.number(estimate,"amount");distance=Inputs.number(estimate,"distanceKm");status="PENDING_PAYMENT";}
        else{hours=Inputs.decimal(body,"hours",BigDecimal.ZERO);Api.require(hours.scale()<=2&&hours.compareTo(Inputs.number(car,"minimumHours"))>=0&&hours.compareTo(new BigDecimal("240"))<=0,"包车时长须符合车型最低时长、不超过240小时且最多两位小数");Api.require(!Inputs.str(body,"itinerary",2000).isBlank(),"请填写包车行程及服务需求");status="PENDING_QUOTE";}
        String no="WA"+java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(now)+UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase(Locale.ROOT);
        long id=db.insert("INSERT INTO orders(order_no,user_id,vehicle_id,vehicle_name,type,status,origin_name,destination_name,origin,destination,appointment_at,passenger_name,passenger_phone,distance_km,hours,amount,remark,itinerary,commission_rate,request_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",no,user,carId,car.get("name"),type,status,originName,destinationName,origin,destination,appointment,passenger,phone,distance,hours,amount,Inputs.str(body,"remark",1000),Inputs.str(body,"itinerary",2000),settings.number("commissionRate"),requestKey.isEmpty()?null:requestKey);
        change(id,user,status,"CREATE","RIDE".equals(type)?"预约已提交，请支付后等待客服派单":"包车预约已提交，请等待客服报价");return get(id,user);
    }
    @Transactional public Map<String,Object> cancel(long id,long user,Map<String,Object> body){var row=locked(id);owner(row,user);workflow.requireAction(row,"cancel");String reason=Inputs.required(body,"reason",500);
        if(Inputs.number(row,"paidAmount").signum()==0)change(id,user,"CANCELLED","CANCEL","订单已取消："+reason);
        else {db.update("UPDATE orders SET previous_status=? WHERE id=?",row.get("status"),id);change(id,user,"CANCEL_REQUESTED","CANCEL_REQUEST","已申请取消，请等待客服审核："+reason);}return get(id,user);
    }
    @Transactional public Map<String,Object> cancelByAdmin(long id,long actor,Map<String,Object> body){
        var row=locked(id);workflow.requireAction(row,"adminCancel");
        String reason=Inputs.required(body,"reason",500);boolean paid=Inputs.number(row,"paidAmount").signum()>0;
        change(id,actor,paid?"REFUNDING":"CANCELLED","ADMIN_CANCEL",paid?"客服已人工取消，待财务退款："+reason:"客服已人工取消订单："+reason);
        return get(id,null);
    }
    @Transactional public Map<String,Object> quote(long id,long actor,Map<String,Object> body){var row=locked(id);workflow.requireAction(row,"quote");Api.require("CHARTER".equals(row.get("type")),"仅包车订单可人工报价");if(body.containsKey("quoteVersion")){int version=Inputs.integer(body,"quoteVersion",-1,0,Integer.MAX_VALUE);if(version!=Inputs.number(row,"quoteVersion").intValue())throw Api.fail(409,"报价已被其他工作人员更新，请刷新订单后重新报价");}BigDecimal amount=Inputs.money(body,"amount");db.update("UPDATE orders SET amount=?,quote_remark=?,quote_version=quote_version+1 WHERE id=?",amount,Inputs.str(body,"remark",1000),id);change(id,actor,"PENDING_CONFIRM","QUOTE","客服报价 "+amount+" 元，请确认报价后支付");return get(id,null);}
    @Transactional public Map<String,Object> accept(long id,long user,Map<String,Object> body){var row=locked(id);owner(row,user);workflow.requireAction(row,"acceptQuote");Api.require(body.containsKey("quoteVersion")&&body.get("quoteVersion")!=null,"请刷新订单并确认最新报价");int version=Inputs.integer(body,"quoteVersion",-1,0,Integer.MAX_VALUE);if(version!=Inputs.number(row,"quoteVersion").intValue())throw Api.fail(409,"客服已更新报价，请刷新订单并确认最新金额");change(id,user,"PENDING_PAYMENT","ACCEPT_QUOTE","已确认报价，请完成支付");return get(id,user);}
    private void requireDevPayment(){if(!auth.dev()||!paymentsEnabled)throw Api.fail(503,"微信支付商户及证书尚未配置，暂不可在线支付或退款；请联系平台客服");}
    @Transactional public Map<String,Object> pay(long id,long user){requireDevPayment();var row=locked(id);owner(row,user);workflow.requireAction(row,"pay");BigDecimal amount=Inputs.number(row,"amount");Api.require(amount.signum()>0,"订单金额无效");
        db.insert("INSERT INTO payments(order_id,order_no,type,mode,amount,status,transaction_no) VALUES (?,?,'PAYMENT','DEV',?,'SUCCESS',?)",id,row.get("orderNo"),amount,"DEV-PAY-"+UUID.randomUUID());db.update("UPDATE orders SET paid_amount=? WHERE id=?",amount,id);change(id,user,"PENDING_DISPATCH","DEV_PAYMENT","开发模拟支付成功，等待客服派单（未发生真实扣款）");return Map.of("mode","DEV","message","开发模式模拟支付，未发生真实扣款","order",get(id,user));}
    @Transactional public Map<String,Object> dispatch(long id,long actor,Map<String,Object> body){var row=locked(id);workflow.requireAction(row,"dispatch");String driver=Inputs.required(body,"driverName",60),phone=Inputs.phone(body,"driverPhone"),plate=Inputs.required(body,"plateNumber",20);db.update("UPDATE orders SET driver_name=?,driver_phone=?,plate_number=? WHERE id=?",driver,phone,plate,id);change(id,actor,"DISPATCHED","DISPATCH","已安排司机 "+driver+"，联系电话 "+phone+"，车牌 "+plate);return get(id,null);}
    @Transactional public Map<String,Object> start(long id,long actor){var row=locked(id);workflow.requireAction(row,"start");change(id,actor,"IN_SERVICE","START","司机已开始服务，祝您出行顺利");return get(id,null);}
    @Transactional public Map<String,Object> complete(long id,long actor){var row=locked(id);workflow.requireAction(row,"complete");commissions.createPending(row);change(id,actor,"COMPLETED","COMPLETE","本次服务已完成，感谢您的信任");return get(id,null);}
    @Transactional public Map<String,Object> terminate(long id,long actor,Map<String,Object> body){var row=locked(id);workflow.requireAction(row,"terminate");String reason=Inputs.required(body,"reason",500);change(id,actor,"REFUNDING","TERMINATE","客服已中止服务并申请全额退款，待财务处理："+reason);return get(id,null);}
    @Transactional public Map<String,Object> cancelReview(long id,long actor,Map<String,Object> body){var row=locked(id);workflow.requireAction(row,"cancelReview");Api.require(body.containsKey("approved")&&body.get("approved") instanceof Boolean,"请选择通过或驳回取消申请");boolean approved=Inputs.bool(body,"approved",false);String reason=approved?Inputs.str(body,"reason",500):Inputs.required(body,"reason",500);
        if(approved)change(id,actor,"REFUNDING","CANCEL_APPROVE","取消申请已通过，待财务退款"+(reason.isEmpty()?"":"："+reason));
        else{String previous=Objects.toString(row.get("previousStatus"),"PENDING_DISPATCH");Api.require(Set.of("PENDING_DISPATCH","DISPATCHED").contains(previous),"订单原状态异常");change(id,actor,previous,"CANCEL_REJECT","取消申请未通过，请联系客服"+(reason.isEmpty()?"":"："+reason));}return get(id,null);}
    @Transactional public Map<String,Object> refund(long id,long actor,Map<String,Object> body){requireDevPayment();var row=locked(id);workflow.requireAction(row,"refund");String reason=Inputs.required(body,"reason",500);BigDecimal paid=Inputs.number(row,"paidAmount");Api.require(paid.signum()>0,"订单没有可退金额");
        commissions.reverseForRefund(row);
        db.insert("INSERT INTO payments(order_id,order_no,type,mode,amount,status,transaction_no,reason) VALUES (?,?,'REFUND','DEV',?,'SUCCESS',?,?)",id,row.get("orderNo"),paid,"DEV-REFUND-"+UUID.randomUUID(),reason);db.update("UPDATE orders SET paid_amount=0 WHERE id=?",id);change(id,actor,"REFUNDED","DEV_REFUND","开发模拟退款完成，金额 "+paid+" 元（未发生真实资金退回）："+reason);return get(id,null);
    }
}
