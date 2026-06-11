package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.CreatePortfolioAssetRequest;
import com.example.finance_hq.portfolio.dto.PortfolioAssetResponse;
import com.example.finance_hq.portfolio.dto.UpdatePortfolioAssetRequest;
import com.example.finance_hq.user.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PortfolioAssetService {

    private final PortfolioAssetRepository repository;

    public PortfolioAssetService(PortfolioAssetRepository repository) {
        this.repository = repository;
    }

    /** Returns up to 500 assets for the given user, ordered by creation date (newest first). */
    @Transactional(readOnly = true)
    public List<PortfolioAssetResponse> findAll(User user) {
        PageRequest page = PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findAllByUserOrderByCreatedAtDesc(user, page).getContent().stream()
                .map(PortfolioAssetResponse::from)
                .toList();
    }

    /** Creates a new portfolio asset; throws if a position for the same ticker already exists. */
    @Transactional
    public PortfolioAssetResponse create(User user, CreatePortfolioAssetRequest req) {
        repository.findByUserAndTicker(user, req.ticker()).ifPresent(existing -> {
            throw new InvalidPortfolioAssetException(
                    "Position for " + req.ticker() + " already exists. Use PATCH to update.");
        });

        PortfolioAsset asset = new PortfolioAsset();
        asset.setUser(user);
        asset.setTicker(req.ticker());
        asset.setAssetGroup(req.assetGroup());
        asset.setShares(req.shares());
        asset.setAvgBuyPricePln(req.avgBuyPricePln());
        asset.setAvgBuyPriceAssetCurrency(req.avgBuyPriceAssetCurrency());
        asset.setPurchaseValuePln(req.purchaseValuePln());
        asset.setPurchaseValueAssetCurrency(req.purchaseValueAssetCurrency());
        asset.setPurchaseSharePercent(req.purchaseSharePercent());

        return PortfolioAssetResponse.from(repository.save(asset));
    }

    /**
     * Patches an existing asset; at least one field must be non-null.
     * Rejects a ticker change that would collide with an existing position.
     */
    @Transactional
    public PortfolioAssetResponse update(User user, UUID id, UpdatePortfolioAssetRequest req) {
        if (req.ticker() == null && req.assetGroup() == null && req.shares() == null
                && req.avgBuyPricePln() == null && req.avgBuyPriceAssetCurrency() == null
                && req.purchaseValuePln() == null && req.purchaseValueAssetCurrency() == null
                && req.purchaseSharePercent() == null) {
            throw new PortfolioAssetValidationException("At least one field must be provided for update");
        }

        PortfolioAsset asset = repository.findByIdAndUser(id, user)
                .orElseThrow(() -> new PortfolioAssetNotFoundException(id));

        if (req.ticker() != null) {
            if (!req.ticker().equals(asset.getTicker())) {
                repository.findByUserAndTicker(user, req.ticker()).ifPresent(existing -> {
                    throw new InvalidPortfolioAssetException(
                            "Position for " + req.ticker() + " already exists. Use PATCH to update.");
                });
            }
            asset.setTicker(req.ticker());
        }
        if (req.assetGroup() != null) asset.setAssetGroup(req.assetGroup());
        if (req.shares() != null) asset.setShares(req.shares());
        if (req.avgBuyPricePln() != null) asset.setAvgBuyPricePln(req.avgBuyPricePln());
        if (req.avgBuyPriceAssetCurrency() != null) asset.setAvgBuyPriceAssetCurrency(req.avgBuyPriceAssetCurrency());
        if (req.purchaseValuePln() != null) asset.setPurchaseValuePln(req.purchaseValuePln());
        if (req.purchaseValueAssetCurrency() != null) asset.setPurchaseValueAssetCurrency(req.purchaseValueAssetCurrency());
        if (req.purchaseSharePercent() != null) asset.setPurchaseSharePercent(req.purchaseSharePercent());

        return PortfolioAssetResponse.from(repository.save(asset));
    }

    /** Removes the asset; throws if the asset does not belong to the given user. */
    @Transactional
    public void delete(User user, UUID id) {
        PortfolioAsset asset = repository.findByIdAndUser(id, user)
                .orElseThrow(() -> new PortfolioAssetNotFoundException(id));
        repository.delete(asset);
    }
}
