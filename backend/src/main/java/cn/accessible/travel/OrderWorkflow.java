package cn.accessible.travel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Shared business capabilities for order reads and writes. HTTP roles remain enforced by controllers. */
@Service
public class OrderWorkflow {
    private static final Set<String> CANCELLABLE = Set.of("PENDING_QUOTE", "PENDING_CONFIRM", "PENDING_PAYMENT", "PENDING_DISPATCH", "DISPATCHED");
    final Db db;
    final SettingsService settings;
    final Auth auth;
    final boolean paymentsEnabled;

    OrderWorkflow(Db db, SettingsService settings, Auth auth, @Value("${app.dev-payments-enabled:true}") boolean paymentsEnabled) { this.db = db; this.settings = settings; this.auth = auth; this.paymentsEnabled = paymentsEnabled; }

    private Map<String,Object> capabilities(Map<String,Object> row, LocalDateTime now, BigDecimal cancelHours) {
        String status = Objects.toString(row.get("status"), "");
        LocalDateTime appointment = (LocalDateTime) row.get("appointmentAt");
        boolean expired = appointment.isBefore(now), hasPaid = Inputs.number(row, "paidAmount").signum() > 0;
        boolean fullyPaid = Inputs.number(row, "amount").signum() > 0 && Inputs.number(row, "paidAmount").compareTo(Inputs.number(row, "amount")) == 0;
        LocalDateTime cutoff = hasPaid ? appointment.minusMinutes(cancelHours.multiply(new BigDecimal("60")).longValue()) : null;
        boolean cancelStatus = CANCELLABLE.contains(status), nearAppointment = hasPaid && now.isAfter(cutoff);
        boolean cancel = cancelStatus && !nearAppointment;
        String cancelReason = !cancelStatus ? "订单当前状态不能自助取消" : nearAppointment ? "距离预约已不足" + cancelHours.stripTrailingZeros().toPlainString() + "小时，请联系客服协助取消" : "";
        var cancelRules = new LinkedHashMap<String,Object>();
        cancelRules.put("allowed", cancel); cancelRules.put("reason", cancelReason); cancelRules.put("cutoffAt", cutoff);
        var actions = new LinkedHashMap<String,Boolean>();
        actions.put("cancel", cancel);
        actions.put("acceptQuote", "PENDING_CONFIRM".equals(status) && !expired);
        boolean paymentsAvailable = auth.dev() && paymentsEnabled;
        actions.put("pay", "PENDING_PAYMENT".equals(status) && !expired && !hasPaid && Inputs.number(row, "amount").signum() > 0 && paymentsAvailable);
        actions.put("quote", "CHARTER".equals(row.get("type")) && Set.of("PENDING_QUOTE", "PENDING_CONFIRM").contains(status) && !expired);
        actions.put("dispatch", Set.of("PENDING_DISPATCH", "DISPATCHED").contains(status) && fullyPaid);
        actions.put("start", "DISPATCHED".equals(status) && fullyPaid);
        actions.put("complete", "IN_SERVICE".equals(status) && fullyPaid);
        actions.put("terminate", "IN_SERVICE".equals(status) && fullyPaid);
        actions.put("cancelReview", "CANCEL_REQUESTED".equals(status));
        actions.put("adminCancel", cancelStatus);
        actions.put("refund", Set.of("REFUNDING", "COMPLETED").contains(status) && hasPaid && paymentsAvailable);
        var result = new LinkedHashMap<String,Object>();
        result.put("actions", actions); result.put("cancelRules", cancelRules); result.put("hasPaid", hasPaid);
        result.put("appointmentExpired", expired); result.put("quoteVersion", Inputs.number(row, "quoteVersion").intValue());
        result.put("paymentUnavailableReason", paymentsAvailable ? "" : "微信支付商户及证书尚未配置，暂不可在线支付或退款；请联系平台客服");
        result.put("nextStep", nextStep(status, expired, nearAppointment && cancelStatus));
        return result;
    }

    private String nextStep(String status, boolean expired, boolean contactForCancellation) {
        if (expired && Set.of("PENDING_QUOTE", "PENDING_CONFIRM", "PENDING_PAYMENT").contains(status)) return "预约时间已过，请取消订单后重新预约";
        String text = switch (status) {
            case "PENDING_QUOTE" -> "等待客服报价，报价后请确认费用";
            case "PENDING_CONFIRM" -> "请确认最新报价后支付";
            case "PENDING_PAYMENT" -> "请完成支付，支付后由客服安排车辆";
            case "PENDING_DISPATCH" -> "已支付，等待客服安排司机";
            case "DISPATCHED" -> "司机已安排，请按预约时间乘车";
            case "IN_SERVICE" -> "正在服务中，如需协助请联系客服";
            case "CANCEL_REQUESTED" -> "取消申请待审核，请等待客服处理";
            case "REFUNDING" -> "等待财务办理退款，请留意退款进度";
            case "REFUNDED" -> "退款处理已完成，可查看退款记录";
            case "CANCELLED" -> "订单已取消，如需用车请重新预约";
            case "COMPLETED" -> "服务已完成，如有疑问请联系客服";
            default -> "请刷新订单查看最新进度";
        };
        return contactForCancellation ? text + "；如需取消请联系客服" : text;
    }

    public void requireAction(Map<String,Object> row, String action) {
        var presentation = capabilities(row, LocalDateTime.now(), settings.number("cancelHours"));
        @SuppressWarnings("unchecked") var actions = (Map<String,Boolean>) presentation.get("actions");
        if (Boolean.TRUE.equals(actions.get(action))) return;
        if (Set.of("quote", "acceptQuote", "pay").contains(action) && Boolean.TRUE.equals(presentation.get("appointmentExpired"))) throw Api.fail(400, "预约时间已过，请取消订单后重新预约");
        if ("cancel".equals(action)) {
            @SuppressWarnings("unchecked") var rules = (Map<String,Object>) presentation.get("cancelRules");
            throw Api.fail(400, rules.get("reason").toString());
        }
        if (Set.of("dispatch", "start", "complete", "terminate").contains(action) && (Inputs.number(row, "amount").signum() <= 0 || Inputs.number(row, "paidAmount").compareTo(Inputs.number(row, "amount")) != 0)) throw Api.fail(400, "订单未足额支付，不能派单、开始、完成或中止服务");
        throw Api.fail(400, "订单当前状态不允许此操作，请刷新后重试");
    }

    public Map<String,Object> present(Map<String,Object> row) { present(List.of(row)); return row; }

    public void present(List<Map<String,Object>> rows) {
        if (rows.isEmpty()) return;
        BigDecimal cancelHours = settings.number("cancelHours"); LocalDateTime now = LocalDateTime.now();
        var args = rows.stream().map(row -> row.get("id")).toArray();
        String slots = String.join(",", Collections.nCopies(rows.size(), "?"));
        var balances = new HashMap<Long,Map<String,Object>>();
        for (var payment : db.list("SELECT order_id, COALESCE(SUM(CASE WHEN type='PAYMENT' THEN amount ELSE 0 END),0) AS total_paid, COALESCE(SUM(CASE WHEN type='REFUND' THEN amount ELSE 0 END),0) AS refunded FROM payments WHERE status='SUCCESS' AND order_id IN (" + slots + ") GROUP BY order_id", args)) balances.put(Inputs.longValue(payment, "orderId"), payment);
        for (var row : rows) {
            row.putAll(capabilities(row, now, cancelHours));
            var payment = balances.get(Inputs.longValue(row, "id"));
            BigDecimal total = payment == null ? BigDecimal.ZERO.setScale(2) : Inputs.number(payment, "totalPaid");
            BigDecimal refunded = payment == null ? BigDecimal.ZERO.setScale(2) : Inputs.number(payment, "refunded");
            row.put("finance", Map.of("totalPaid", total, "refunded", refunded, "netPaid", total.subtract(refunded)));
        }
    }
}
