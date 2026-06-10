package com.example.finance_hq.portfolio;

import java.util.UUID;

public class PortfolioAssetNotFoundException extends RuntimeException {
    public PortfolioAssetNotFoundException(UUID id) {
        super("Portfolio asset not found: " + id);
    }
}
