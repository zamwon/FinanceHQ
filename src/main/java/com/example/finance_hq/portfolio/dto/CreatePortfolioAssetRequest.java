package com.example.finance_hq.portfolio.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreatePortfolioAssetRequest(
        @NotBlank @Size(max = 20) String ticker,
        @NotBlank @Size(max = 100) String assetGroup,
        @NotNull @DecimalMin("0.00000001") BigDecimal shares,
        @NotNull @DecimalMin("0.00000001") BigDecimal avgBuyPricePln,
        @NotNull @DecimalMin("0.00000001") BigDecimal avgBuyPriceAssetCurrency,
        @NotNull @DecimalMin("0.00000001") BigDecimal purchaseValuePln,
        @NotNull @DecimalMin("0.00000001") BigDecimal purchaseValueAssetCurrency,
        @DecimalMin("0") @DecimalMax("100") BigDecimal purchaseSharePercent
) {}
