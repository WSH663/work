ALTER TABLE withdrawals ADD COLUMN request_key VARCHAR(64);
CREATE UNIQUE INDEX uk_withdrawals_user_request ON withdrawals(user_id, request_key);
