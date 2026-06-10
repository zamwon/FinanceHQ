package com.example.finance_hq.portfolio.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record UpdatePortfolioAssetRequest(
        @Size(max = 20) String ticker,
        @Size(max = 100) String assetGroup,
        @DecimalMin("0.00000001") BigDecimal shares,
        @DecimalMin("0.00000001") BigDecimal avgBuyPricePln,
        @DecimalMin("0.00000001") BigDecimal avgBuyPriceAssetCurrency,
        @DecimalMin("0.00000001") BigDecimal purchaseValuePln,
        @DecimalMin("0.00000001") BigDecimal purchaseValueAssetCurrency,
        @DecimalMin("0") @DecimalMax("100") BigDecimal purchaseSharePercent
) {}
