package com.example.finance_hq.portfolio.dto;

import com.example.finance_hq.portfolio.PortfolioAsset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record PortfolioAssetResponse(
        UUID id,
        String ticker,
        String assetGroup,
        BigDecimal shares,
        BigDecimal avgBuyPricePln,
        BigDecimal avgBuyPriceAssetCurrency,
        BigDecimal purchaseValuePln,
        BigDecimal purchaseValueAssetCurrency,
        BigDecimal purchaseSharePercent,
        BigDecimal currentPriceUsd,
        BigDecimal currentPricePln,
        BigDecimal currentValuePln,
        BigDecimal currentSharePercent,
        Instant priceLastUpdatedAt,
        Instant createdAt
) {
    public static PortfolioAssetResponse from(PortfolioAsset a) {
        BigDecimal currentValuePln = null;
        if (a.getCurrentPricePln() != null) {
            currentValuePln = a.getShares().multiply(a.getCurrentPricePln())
                    .setScale(4, RoundingMode.HALF_UP);
        }
        return new PortfolioAssetResponse(
                a.getId(),
                a.getTicker(),
                a.getAssetGroup(),
                a.getShares(),
                a.getAvgBuyPricePln(),
                a.getAvgBuyPriceAssetCurrency(),
                a.getPurchaseValuePln(),
                a.getPurchaseValueAssetCurrency(),
                a.getPurchaseSharePercent(),
                a.getCurrentPriceUsd(),
                a.getCurrentPricePln(),
                currentValuePln,
                a.getCurrentSharePercent(),
                a.getPriceLastUpdatedAt(),
                a.getCreatedAt()
        );
    }
}
