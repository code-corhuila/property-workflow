-- Back to UUID. It fails if a stored correlation id is not a UUID: in qa or main that
-- rollback is then a decision, recorded in 04_tcl/01_manual_recoveries (migration-strategy.md).
ALTER TABLE workflow.saga_instance DROP CONSTRAINT IF EXISTS chk_saga_instance_correlation_id;

ALTER TABLE workflow.saga_instance
  ALTER COLUMN correlation_id TYPE UUID USING correlation_id::uuid;
