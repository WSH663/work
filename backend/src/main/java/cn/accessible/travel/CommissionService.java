package cn.accessible.travel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommissionService {
    private static final Logger log=LoggerFactory.getLogger(CommissionService.class);
    public static final int SETTLEMENT_DAYS=30;
    final Db db;
    final PlatformService platform;
    final TransactionTemplate transactions;

    CommissionService(Db db,PlatformService platform,PlatformTransactionManager manager) {
        this.db=db; this.platform=platform; this.transactions=new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The caller owns the order row lock and transaction. No withdrawable balance is credited here. */
    public void createPending(Map<String,Object> order) {
        var user=db.one("SELECT referrer_id FROM users WHERE id=?",order.get("userId"));
        if(user.get("referrerId")==null)return;
        BigDecimal amount=Inputs.number(order,"paidAmount").multiply(Inputs.number(order,"commissionRate")).setScale(2,RoundingMode.DOWN);
        if(amount.signum()<=0)return;
        if(db.maybe("SELECT id FROM commissions WHERE order_id=? FOR UPDATE",order.get("id"))!=null)return;
        long referrer=Inputs.longValue(user,"referrerId");
        LocalDateTime eligible=LocalDateTime.now().plusDays(SETTLEMENT_DAYS);
        db.insert("INSERT INTO commissions(user_id,referred_user_id,order_id,order_no,amount,status,eligible_at) VALUES (?,?,?,?,?,'PENDING',?)",referrer,order.get("userId"),order.get("id"),order.get("orderNo"),amount,eligible);
        platform.message(referrer,"佣金待结算","推广订单 "+order.get("orderNo")+" 已完成，佣金 "+amount+" 元将在完成30日后且订单未退款时转入可提现余额");
    }

    /** The caller owns the order row lock and transaction. Settled funds follow the existing recovery policy. */
    public void reverseForRefund(Map<String,Object> order) {
        var commission=db.maybe("SELECT * FROM commissions WHERE order_id=? FOR UPDATE",order.get("id"));
        if(commission==null||"REVERSED".equals(commission.get("status")))return;
        long referrer=Inputs.longValue(commission,"userId"); BigDecimal amount=Inputs.number(commission,"amount");
        if("PENDING".equals(commission.get("status"))) {
            db.update("UPDATE commissions SET status='REVERSED' WHERE id=?",commission.get("id"));
            platform.message(referrer,"待结算佣金已撤销","订单 "+order.get("orderNo")+" 已退款，待结算佣金 "+amount+" 元已撤销");
            return;
        }
        Api.require("SETTLED".equals(commission.get("status")),"佣金状态异常，请联系财务核对后退款");
        var profile=db.one("SELECT * FROM users WHERE id=? FOR UPDATE",referrer);
        Api.require(Inputs.number(profile,"availableAmount").compareTo(amount)>=0,"佣金已提现或冻结，需先完成财务追偿再退款");
        db.update("UPDATE users SET available_amount=available_amount-? WHERE id=?",amount,referrer);
        db.update("UPDATE commissions SET status='REVERSED' WHERE id=?",commission.get("id"));
        platform.message(referrer,"佣金已冲回","订单 "+order.get("orderNo")+" 已退款，相关佣金 "+amount+" 元已冲回");
    }

    /** A small batch with one transaction per item also supports multiple application instances safely. */
    public int settleDue() {
        LocalDateTime now=LocalDateTime.now(); int count=0;
        var due=db.list("SELECT c.id,c.order_id FROM commissions c JOIN orders o ON o.id=c.order_id WHERE c.status='PENDING' AND c.eligible_at<=? AND o.status='COMPLETED' ORDER BY c.eligible_at,c.id LIMIT 100",now);
        for(var candidate:due) {
            try {
                Boolean settled=transactions.execute(transaction->settleLocked(Inputs.longValue(candidate,"orderId"),Inputs.longValue(candidate,"id"),now));
                if(Boolean.TRUE.equals(settled))count++;
            } catch(RuntimeException failure) {
                log.warn("Commission settlement deferred for id {} ({})",candidate.get("id"),failure.getClass().getSimpleName());
            }
        }
        return count;
    }

    private boolean settleLocked(long orderId,long commissionId,LocalDateTime now) {
        var order=db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",orderId);
        var commission=db.one("SELECT * FROM commissions WHERE id=? AND order_id=? FOR UPDATE",commissionId,orderId);
        Object eligible=commission.get("eligibleAt");
        if(!"PENDING".equals(commission.get("status"))||!(eligible instanceof LocalDateTime time)||time.isAfter(now))return false;
        if(!"COMPLETED".equals(order.get("status"))||Inputs.number(order,"amount").signum()<=0||Inputs.number(order,"paidAmount").compareTo(Inputs.number(order,"amount"))!=0)return false;
        if(db.count("SELECT COUNT(*) FROM payments WHERE order_id=? AND type='REFUND' AND status='SUCCESS'",orderId)>0)return false;
        long referrer=Inputs.longValue(commission,"userId"); BigDecimal amount=Inputs.number(commission,"amount");
        Api.require(amount.signum()>0,"佣金金额异常");
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",referrer);
        db.update("UPDATE users SET available_amount=available_amount+? WHERE id=?",amount,referrer);
        db.update("UPDATE commissions SET status='SETTLED',settled_at=? WHERE id=?",now,commissionId);
        platform.message(referrer,"佣金已结算","推广订单 "+order.get("orderNo")+" 已完成30日，佣金 "+amount+" 元已转入可提现余额");
        return true;
    }

    @Scheduled(initialDelayString="${app.commission-settlement-initial-delay-ms:60000}",fixedDelayString="${app.commission-settlement-delay-ms:60000}")
    public void scheduledSettlement() { settleDue(); }
}
