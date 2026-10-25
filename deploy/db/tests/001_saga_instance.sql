-- The schema workflow (06-data/models.md, ADR-010):
--   a repeated start never creates a second Saga: uq_saga_instance_idempotency_key;
--   only the documented status and type;
--   workflow_app reads, inserts and updates, cannot delete nor change structure,
--   and writes only in workflow (Annex J.10).
-- Everything runs in one transaction that is rolled back.
BEGIN;
SET LOCAL ROLE workflow_app;

INSERT INTO workflow.saga_instance (id, saga_type, idempotency_key, guest_id, correlation_id)
VALUES ('35920dc9-bd55-41a0-8e41-5832377383e5', 'reservar', 'reservar-0001',
        '22222222-2222-4222-8222-222222222222', '550e8400-e29b-41d4-a716-446655440000');
UPDATE workflow.saga_instance
SET completed_steps = completed_steps || 'crear-reserva'::text,
    reservation_id = '55555555-5555-4555-8555-555555555555', updated_at = now()
WHERE id = '35920dc9-bd55-41a0-8e41-5832377383e5';

DO $$
BEGIN
  INSERT INTO workflow.saga_instance (saga_type, idempotency_key, guest_id, correlation_id)
  VALUES ('reservar', 'reservar-0001', '22222222-2222-4222-8222-222222222222',
          '550e8400-e29b-41d4-a716-446655440001');
  RAISE EXCEPTION 'uq_saga_instance_idempotency_key not enforced: a second Saga with the same key was stored';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'uq_saga_instance_idempotency_key: a repeated start rejected';
END
$$;

DO $$
BEGIN
  UPDATE workflow.saga_instance SET status = 'PAUSED';
  RAISE EXCEPTION 'chk_saga_instance_status not enforced: an unknown status was stored';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'chk_saga_instance_status: an unknown status rejected';
END
$$;

DO $$
BEGIN
  INSERT INTO workflow.saga_instance (saga_type, idempotency_key, guest_id, correlation_id)
  VALUES ('place-order', 'reservar-0002', '22222222-2222-4222-8222-222222222222',
          '550e8400-e29b-41d4-a716-446655440002');
  RAISE EXCEPTION 'chk_saga_instance_saga_type not enforced: an unknown Saga type was stored';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'chk_saga_instance_saga_type: a Saga type other than reservar rejected';
END
$$;

DO $$
BEGIN
  DELETE FROM workflow.saga_instance;
  RAISE EXCEPTION 'workflow_app could delete';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'workflow_app cannot delete';
END
$$;

DO $$
BEGIN
  CREATE TABLE workflow.not_allowed (id int);
  RAISE EXCEPTION 'workflow_app could create a table';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'workflow_app cannot change structure';
END
$$;

RESET ROLE;

-- Annex J.10: the schemas where workflow_app can write.
DO $$
DECLARE
  schemas text;
BEGIN
  SELECT string_agg(DISTINCT n.nspname, ', ') INTO schemas
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE c.relkind = 'r'
    AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
    AND has_schema_privilege('workflow_app', n.nspname, 'USAGE')
    AND (has_table_privilege('workflow_app', c.oid, 'INSERT')
         OR has_table_privilege('workflow_app', c.oid, 'UPDATE')
         OR has_table_privilege('workflow_app', c.oid, 'DELETE'));
  IF schemas IS DISTINCT FROM 'workflow' THEN
    RAISE EXCEPTION 'workflow_app writes in: %', schemas;
  END IF;
  RAISE NOTICE 'Annex J.10: workflow_app writes only in workflow';
END
$$;

ROLLBACK;
