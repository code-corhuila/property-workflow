-- The state of each Saga, as in 06-data/models.md (ADR-010). It is updated after
-- every step and every compensation. It holds no business data: only the
-- identifiers the participants returned.
CREATE TABLE workflow.saga_instance (
  id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  saga_type        TEXT        NOT NULL
                   CONSTRAINT chk_saga_instance_saga_type CHECK (saga_type IN ('reservar')),
  status           TEXT        NOT NULL DEFAULT 'RUNNING'
                   CONSTRAINT chk_saga_instance_status
                   CHECK (status IN ('RUNNING', 'COMPLETED', 'COMPENSATED', 'FAILED')),
  idempotency_key  TEXT        NOT NULL
                   CONSTRAINT chk_saga_instance_idempotency_key
                   CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
  guest_id         UUID        NOT NULL,   -- who started the Saga; owned by identity, no FK
  completed_steps  TEXT[]      NOT NULL DEFAULT '{}',
  failed_step      TEXT,
  error_detail     TEXT,                   -- internal: never returned by the API
  reservation_id   UUID,                   -- returned by Booking in step crear-reserva
  payment_id       UUID,                   -- returned by Payment in step cobrar
  refund_id        UUID,                   -- returned by Payment when cobrar is compensated
  correlation_id   UUID        NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT uq_saga_instance_idempotency_key UNIQUE (idempotency_key)
);
