-- correlation_id keeps the X-Correlation-Id as it arrived: any text of 1 to 128
-- characters (ddl-alter-001). Runs in one transaction that is rolled back.
BEGIN;
SET LOCAL ROLE workflow_app;

INSERT INTO workflow.saga_instance (saga_type, idempotency_key, guest_id, correlation_id)
VALUES ('reservar', 'reservar-text-0001', '22222222-2222-4222-8222-222222222222', 'e2e-saga-1');

DO $$
BEGIN
  INSERT INTO workflow.saga_instance (saga_type, idempotency_key, guest_id, correlation_id)
  VALUES ('reservar', 'reservar-text-0002', '22222222-2222-4222-8222-222222222222', '');
  RAISE EXCEPTION 'chk_saga_instance_correlation_id not enforced: an empty correlation id was stored';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'chk_saga_instance_correlation_id: an empty correlation id rejected';
END
$$;

DO $$
BEGIN
  INSERT INTO workflow.saga_instance (saga_type, idempotency_key, guest_id, correlation_id)
  VALUES ('reservar', 'reservar-text-0003', '22222222-2222-4222-8222-222222222222', repeat('x', 129));
  RAISE EXCEPTION 'chk_saga_instance_correlation_id not enforced: 129 characters were stored';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'chk_saga_instance_correlation_id: 129 characters rejected';
END
$$;

RESET ROLE;
ROLLBACK;
