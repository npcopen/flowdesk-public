-- Executed once by the controlled bootstrap DBA after all five Flyway jobs succeed.
-- SOURCE is a mysql client command; each service file remains independently auditable.
SOURCE /grants/iam.sql;
SOURCE /grants/ticket.sql;
SOURCE /grants/workflow.sql;
SOURCE /grants/alert.sql;
SOURCE /grants/notification.sql;
