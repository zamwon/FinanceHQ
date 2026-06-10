package com.example.finance_hq.portfolio;

import com.example.finance_hq.user.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "portfolio_assets")
public class PortfolioAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(name = "asset_group", nullable = false, length = 100)
    private String assetGroup;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal shares;

    @Column(name = "avg_buy_price_pln", nullable = false, precision = 20, scale = 4)
    private BigDecimal avgBuyPricePln;

    @Column(name = "avg_buy_price_asset_currency", nullable = false, precision = 20, scale = 8)
    private BigDecimal avgBuyPriceAssetCurrency;

    @Column(name = "purchase_value_pln", nullable = false, precision = 20, scale = 4)
    private BigDecimal purchaseValuePln;

    @Column(name = "purchase_value_asset_currency", nullable = false, precision = 20, scale = 8)
    private BigDecimal purchaseValueAssetCurrency;

    @Column(name = "purchase_share_percent", precision = 7, scale = 4)
    private BigDecimal purchaseSharePercent;

    @Column(name = "current_price_usd", precision = 20, scale = 8)
    private BigDecimal currentPriceUsd;

    @Column(name = "current_price_pln", precision = 20, scale = 4)
    private BigDecimal currentPricePln;

    @Column(name = "current_share_percent", precision = 7, scale = 4)
    private BigDecimal currentSharePercent;

    @Column(name = "price_last_updated_at")
    private Instant priceLastUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PortfolioAsset() {}

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getTicker() { return ticker; }
    public String getAssetGroup() { return assetGroup; }
    public BigDecimal getShares() { return shares; }
    public BigDecimal getAvgBuyPricePln() { return avgBuyPricePln; }
    public BigDecimal getAvgBuyPriceAssetCurrency() { return avgBuyPriceAssetCurrency; }
    public BigDecimal getPurchaseValuePln() { return purchaseValuePln; }
    public BigDecimal getPurchaseValueAssetCurrency() { return purchaseValueAssetCurrency; }
    public BigDecimal getPurchaseSharePercent() { return purchaseSharePercent; }
    public BigDecimal getCurrentPriceUsd() { return currentPriceUsd; }
    public BigDecimal getCurrentPricePln() { return currentPricePln; }
    public BigDecimal getCurrentSharePercent() { return currentSharePercent; }
    public Instant getPriceLastUpdatedAt() { return priceLastUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setUser(User user) { this.user = user; }
    public void setTicker(String ticker) { this.ticker = ticker; }
    public void setAssetGroup(String assetGroup) { this.assetGroup = assetGroup; }
    public void setShares(BigDecimal shares) { this.shares = shares; }
    public void setAvgBuyPricePln(BigDecimal avgBuyPricePln) { this.avgBuyPricePln = avgBuyPricePln; }
    public void setAvgBuyPriceAssetCurrency(BigDecimal avgBuyPriceAssetCurrency) { this.avgBuyPriceAssetCurrency = avgBuyPriceAssetCurrency; }
    public void setPurchaseValuePln(BigDecimal purchaseValuePln) { this.purchaseValuePln = purchaseValuePln; }
    public void setPurchaseValueAssetCurrency(BigDecimal purchaseValueAssetCurrency) { this.purchaseValueAssetCurrency = purchaseValueAssetCurrency; }
    public void setPurchaseSharePercent(BigDecimal purchaseSharePercent) { this.purchaseSharePercent = purchaseSharePercent; }
    public void setCurrentPriceUsd(BigDecimal currentPriceUsd) { this.currentPriceUsd = currentPriceUsd; }
    public void setCurrentPricePln(BigDecimal currentPricePln) { this.currentPricePln = currentPricePln; }
    public void setCurrentSharePercent(BigDecimal currentSharePercent) { this.currentSharePercent = currentSharePercent; }
    public void setPriceLastUpdatedAt(Instant priceLastUpdatedAt) { this.priceLastUpdatedAt = priceLastUpdatedAt; }
}
