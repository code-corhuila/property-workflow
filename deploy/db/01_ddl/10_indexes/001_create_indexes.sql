-- Index of 06-data/models.md. The idempotency key is already indexed by
-- uq_saga_instance_idempotency_key.
-- "the Sagas to recover at start-up, and the ones a person must look at"
CREATE INDEX idx_saga_instance_open ON workflow.saga_instance (status)
  WHERE status IN ('RUNNING', 'FAILED');
