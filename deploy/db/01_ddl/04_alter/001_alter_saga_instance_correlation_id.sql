-- correlation_id is the X-Correlation-Id of the start request: any text of 1 to 128
-- characters (_shared.yaml, CorrelationIdHeader), not only a UUID. As corrected in
-- property-docs (06-data/models.md, data-dictionary.md). The changeset of D2 is never edited.
ALTER TABLE workflow.saga_instance
  ALTER COLUMN correlation_id TYPE TEXT USING correlation_id::text;

ALTER TABLE workflow.saga_instance
  ADD CONSTRAINT chk_saga_instance_correlation_id
  CHECK (char_length(correlation_id) BETWEEN 1 AND 128);
