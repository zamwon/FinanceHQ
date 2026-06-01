ALTER TABLE notification_log DROP CONSTRAINT notification_log_status_check;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED'));
