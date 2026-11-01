package cn.accessible.travel;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Counts business work requiring a member of staff; these are not unread messages. */
@Service
public class AdminTodoService {
    static final String OPERATIONS_WHERE = "((status='PENDING_QUOTE' AND type='CHARTER' AND appointment_at>=?) OR status IN ('PENDING_DISPATCH','CANCEL_REQUESTED'))";
    final Db db;

    AdminTodoService(Db db) { this.db = db; }

    @Transactional(readOnly=true)
    public Map<String,Object> summary(String role) {
        boolean operations = Set.of("ADMIN", "OPERATOR").contains(role);
        boolean finance = Set.of("ADMIN", "FINANCE").contains(role);
        if (!operations && !finance) throw Api.fail(403, "当前账号没有此操作权限");
        var columns = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (operations) {
            columns.add("(SELECT COUNT(*) FROM orders WHERE status='PENDING_QUOTE' AND type='CHARTER' AND appointment_at>=?) AS pending_quotes");
            args.add(LocalDateTime.now());
            columns.add("(SELECT COUNT(*) FROM orders WHERE status='PENDING_DISPATCH') AS pending_dispatch");
            columns.add("(SELECT COUNT(*) FROM orders WHERE status='CANCEL_REQUESTED') AS cancel_requests");
        }
        if (finance) {
            columns.add("(SELECT COUNT(*) FROM orders WHERE status='REFUNDING') AS pending_refunds");
            columns.add("(SELECT COUNT(*) FROM withdrawals WHERE status='PENDING') AS pending_withdrawals");
            columns.add("(SELECT COUNT(*) FROM withdrawals WHERE status='APPROVED') AS approved_withdrawals");
        }
        // One statement gives every badge the same database snapshot, without fetching business rows.
        var counts = db.one("SELECT " + String.join(",", columns), args.toArray());
        var result = new LinkedHashMap<String,Object>();
        long total = 0;
        if (operations) {
            var group = group(counts, "pendingQuotes", "pendingDispatch", "cancelRequests");
            result.put("operations", group); total += group.get("total");
        }
        if (finance) {
            var group = group(counts, "pendingRefunds", "pendingWithdrawals", "approvedWithdrawals");
            result.put("finance", group); total += group.get("total");
        }
        result.put("total", total);
        return result;
    }

    private Map<String,Long> group(Map<String,Object> counts, String... keys) {
        var result = new LinkedHashMap<String,Long>();
        long total = 0;
        for (String key : keys) {
            long value = ((Number) counts.get(key)).longValue();
            result.put(key, value); total += value;
        }
        result.put("total", total);
        return result;
    }
}
