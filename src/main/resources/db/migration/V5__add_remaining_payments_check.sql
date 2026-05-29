ALTER TABLE obligations
    ADD CONSTRAINT chk_remaining_payments_positive
        CHECK (remaining_payments IS NULL OR remaining_payments > 0);
