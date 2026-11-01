package cn.accessible.travel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Integration boundary for WeChat subscription messages. In-app messages are the current delivery channel. */
public interface SubscriptionNotificationAdapter {
    String mode();
    void onMessageCreated(long userId, String title, String content);
}

@Component
class UnconfiguredWechatSubscriptionAdapter implements SubscriptionNotificationAdapter {
    // Template IDs are configurable now; adding credentials alone does not claim a working WeChat integration.
    final String orderTemplateId,refundTemplateId,withdrawalTemplateId;
    UnconfiguredWechatSubscriptionAdapter(
            @Value("${app.notifications.order-template-id:}") String orderTemplateId,
            @Value("${app.notifications.refund-template-id:}") String refundTemplateId,
            @Value("${app.notifications.withdrawal-template-id:}") String withdrawalTemplateId) {
        this.orderTemplateId=orderTemplateId;this.refundTemplateId=refundTemplateId;this.withdrawalTemplateId=withdrawalTemplateId;
    }
    public String mode(){return "IN_APP_ONLY";}
    public void onMessageCreated(long userId,String title,String content){
        // Replace with an outbox-backed sender after template approval and user subscription consent are available.
    }
}
