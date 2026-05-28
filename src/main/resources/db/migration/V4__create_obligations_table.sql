CREATE TABLE obligations (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    amount         NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    category       VARCHAR(20) NOT NULL,
    period         VARCHAR(20) NOT NULL,
    payment_day    SMALLINT NOT NULL CHECK (payment_day BETWEEN 1 AND 31),
    end_date       DATE,
    remaining_payments INT,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_obligations_user_id ON obligations(user_id);
