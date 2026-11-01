ALTER TABLE orders ADD COLUMN request_key VARCHAR(64);
ALTER TABLE orders ADD COLUMN quote_version INT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_orders_user_request ON orders(user_id, request_key);
