package com.example.finance_hq.portfolio;

import com.example.finance_hq.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioAssetRepository extends JpaRepository<PortfolioAsset, UUID> {

    @Override
    default Optional<PortfolioAsset> findById(UUID id) {
        throw new UnsupportedOperationException(
                "Direct ID lookup bypasses ownership. Use findByIdAndUser(UUID, User) instead.");
    }

    List<PortfolioAsset> findAllByUserOrderByCreatedAtDesc(User user);

    Optional<PortfolioAsset> findByIdAndUser(UUID id, User user);

    Optional<PortfolioAsset> findByUserAndTicker(User user, String ticker);

    @Query("SELECT MAX(a.priceLastUpdatedAt) FROM PortfolioAsset a WHERE a.user = :user")
    Optional<Instant> findMaxPriceLastUpdatedAtByUser(@Param("user") User user);
}
