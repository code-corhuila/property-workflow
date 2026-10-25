REVOKE workflow_writer FROM workflow_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow REVOKE ALL ON TABLES FROM workflow_reader, workflow_writer;
REVOKE ALL ON ALL TABLES IN SCHEMA workflow FROM workflow_reader, workflow_writer;
REVOKE USAGE ON SCHEMA workflow FROM workflow_reader, workflow_writer;
