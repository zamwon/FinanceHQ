CREATE TABLE portfolio_assets (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ticker                       VARCHAR(20) NOT NULL,
    asset_group                  VARCHAR(100) NOT NULL,
    shares                       DECIMAL(20, 8) NOT NULL,
    avg_buy_price_pln             DECIMAL(20, 4) NOT NULL,
    avg_buy_price_asset_currency  DECIMAL(20, 8) NOT NULL,
    purchase_value_pln            DECIMAL(20, 4) NOT NULL,
    purchase_value_asset_currency DECIMAL(20, 8) NOT NULL,
    purchase_share_percent        DECIMAL(7, 4),
    current_price_usd             DECIMAL(20, 8),
    current_price_pln             DECIMAL(20, 4),
    current_share_percent         DECIMAL(7, 4),
    price_last_updated_at         TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_portfolio_user_ticker UNIQUE (user_id, ticker)
);

CREATE INDEX idx_portfolio_assets_user_id ON portfolio_assets(user_id);
