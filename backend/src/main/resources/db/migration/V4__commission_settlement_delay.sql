ALTER TABLE commissions ADD COLUMN eligible_at TIMESTAMP;
ALTER TABLE commissions ADD COLUMN settled_at TIMESTAMP;
UPDATE commissions SET eligible_at=created_at, settled_at=created_at WHERE status='SETTLED';
CREATE INDEX idx_commissions_due ON commissions(status, eligible_at);
