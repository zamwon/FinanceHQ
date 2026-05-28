CREATE TABLE obligations (
    id             UUID NOT NULL PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    amount         NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    category       VARCHAR(20) NOT NULL CHECK (category IN ('ESSENTIAL', 'IMPORTANT', 'OPTIONAL')),
    period         VARCHAR(20) NOT NULL CHECK (period IN ('RECURRING', 'FIXED_TERM')),
    payment_day    SMALLINT NOT NULL CHECK (payment_day BETWEEN 1 AND 31),
    end_date       DATE,
    remaining_payments INT,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_fixed_term_fields CHECK (
        period != 'FIXED_TERM' OR (end_date IS NOT NULL AND remaining_payments IS NOT NULL)
    )
);

CREATE INDEX idx_obligations_user_id ON obligations(user_id);
