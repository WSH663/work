-- Existing messages remain readable; newly emitted order notifications can open the order.
ALTER TABLE messages ADD COLUMN order_id BIGINT;
ALTER TABLE messages ADD CONSTRAINT fk_messages_order FOREIGN KEY (order_id) REFERENCES orders(id);
CREATE INDEX idx_messages_order ON messages(order_id);
