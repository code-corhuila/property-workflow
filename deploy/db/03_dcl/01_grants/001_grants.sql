-- Least privilege. The writer reads, inserts and updates: a Saga is updated after
-- every step and is never deleted, and nobody but the runner changes structure
-- (migration-strategy.md). The workflow never reads a domain schema (ADR-010).
GRANT USAGE ON SCHEMA workflow TO workflow_reader, workflow_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA workflow TO workflow_reader;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA workflow TO workflow_writer;
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT ON TABLES TO workflow_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT, INSERT, UPDATE ON TABLES TO workflow_writer;

-- The workflow connects as workflow_app, created by property-infra-postgres (ADR-010).
GRANT workflow_writer TO workflow_app;
