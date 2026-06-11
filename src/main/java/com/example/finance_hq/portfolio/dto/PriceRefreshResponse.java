package com.example.finance_hq.portfolio.dto;

import java.util.List;

public record PriceRefreshResponse(boolean refreshed, List<PortfolioAssetResponse> assets) {}
