CREATE TABLE transactions (
    id                 UUID          NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    obligation_id      UUID          REFERENCES obligations(id) ON DELETE SET NULL,
    type               VARCHAR(10)   NOT NULL CHECK (type IN ('EXPENSE', 'INCOME')),
    category           VARCHAR(50)   NOT NULL,
    amount             NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    description        VARCHAR(255),
    period             VARCHAR(20)   CHECK (period IN ('RECURRING', 'FIXED_TERM')),
    date               DATE,
    payment_day        INT           CHECK (payment_day BETWEEN 1 AND 31),
    end_date           DATE,
    remaining_payments INT           CHECK (remaining_payments > 0),
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_one_off_has_date      CHECK (period IS NOT NULL OR date IS NOT NULL),
    CONSTRAINT chk_recurring_payment_day CHECK (period IS NULL OR payment_day IS NOT NULL),
    CONSTRAINT chk_fixed_term_complete   CHECK (period != 'FIXED_TERM' OR (end_date IS NOT NULL AND remaining_payments IS NOT NULL)),
    CONSTRAINT chk_category_expense      CHECK (type != 'EXPENSE' OR category IN ('HOUSING','FOOD','TRANSPORT','UTILITIES','HEALTH','ENTERTAINMENT','OTHER')),
    CONSTRAINT chk_category_income       CHECK (type != 'INCOME' OR category IN ('SALARY','FREELANCE','INVESTMENT','RENTAL','OTHER'))
);
CREATE INDEX idx_transactions_user_id   ON transactions(user_id);
CREATE INDEX idx_transactions_user_date ON transactions(user_id, date) WHERE date IS NOT NULL;
