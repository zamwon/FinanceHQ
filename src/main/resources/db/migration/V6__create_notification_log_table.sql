CREATE TABLE notification_log (
    id            UUID        NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    obligation_id UUID        NOT NULL REFERENCES obligations(id) ON DELETE CASCADE,
    due_date      DATE        NOT NULL,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMP,
    CONSTRAINT uq_notification_obligation_due UNIQUE (obligation_id, due_date)
);
CREATE INDEX idx_notification_log_status_failed ON notification_log(status) WHERE status = 'FAILED';
