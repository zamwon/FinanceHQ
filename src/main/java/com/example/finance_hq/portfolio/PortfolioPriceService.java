package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.PortfolioAssetResponse;
import com.example.finance_hq.portfolio.dto.PriceRefreshResponse;
import com.example.finance_hq.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class PortfolioPriceService {

    private final RestClient.Builder restClientBuilder;
    private final PortfolioAssetRepository repository;
    private final CoinGeckoIdMapper coinGeckoIdMapper;
    private final String twelveDataBaseUrl;
    private final String twelveDataApiKey;
    private final String yahooFinanceBaseUrl;
    private final long staleMinutes;

    public PortfolioPriceService(
            @Qualifier("portfolioRestClientBuilder") RestClient.Builder restClientBuilder,
            PortfolioAssetRepository repository,
            CoinGeckoIdMapper coinGeckoIdMapper,
            @Value("${twelvedata.base.url}") String twelveDataBaseUrl,
            @Value("${twelvedata.api.key}") String twelveDataApiKey,
            @Value("${yahoo.finance.base.url}") String yahooFinanceBaseUrl,
            @Value("${portfolio.price.stale-minutes}") long staleMinutes) {
        this.restClientBuilder = restClientBuilder;
        this.repository = repository;
        this.coinGeckoIdMapper = coinGeckoIdMapper;
        this.twelveDataBaseUrl = twelveDataBaseUrl;
        this.twelveDataApiKey = twelveDataApiKey;
        this.yahooFinanceBaseUrl = yahooFinanceBaseUrl;
        this.staleMinutes = staleMinutes;
    }

    @Transactional
    public PriceRefreshResponse refreshIfStale(User user) {
        List<PortfolioAsset> assets = repository.findAllByUserOrderByCreatedAtDesc(user);

        if (assets.isEmpty()) {
            return new PriceRefreshResponse(false, List.of());
        }

        Optional<Instant> maxUpdated = repository.findMaxPriceLastUpdatedAtByUser(user);
        if (maxUpdated.isPresent() &&
                Instant.now().minus(staleMinutes, ChronoUnit.MINUTES).isBefore(maxUpdated.get())) {
            return new PriceRefreshResponse(false, assets.stream()
                    .map(PortfolioAssetResponse::from)
                    .toList());
        }

        // GPW/Warsaw stocks (/XWAR suffix) use Yahoo Finance; everything else (stocks + crypto) uses Twelve Data
        List<PortfolioAsset> gpwAssets = assets.stream()
                .filter(a -> a.getTicker().contains("/XWAR"))
                .toList();
        List<PortfolioAsset> twelveDataAssets = assets.stream()
                .filter(a -> !a.getTicker().contains("/XWAR"))
                .toList();

        Map<String, BigDecimal> usdPrices = new HashMap<>();
        Map<String, BigDecimal> plnPrices = new HashMap<>();

        if (!twelveDataAssets.isEmpty()) {
            fetchTwelveDataPrices(twelveDataAssets, usdPrices, plnPrices);
        }
        if (!gpwAssets.isEmpty()) {
            fetchYahooFinancePrices(gpwAssets, plnPrices);
        }

        Instant now = Instant.now();
        for (PortfolioAsset asset : assets) {
            String ticker = asset.getTicker();
            boolean priceFound = false;
            if (usdPrices.containsKey(ticker)) {
                asset.setCurrentPriceUsd(usdPrices.get(ticker));
                priceFound = true;
            }
            if (plnPrices.containsKey(ticker)) {
                asset.setCurrentPricePln(plnPrices.get(ticker));
                priceFound = true;
            }
            if (priceFound) {
                asset.setPriceLastUpdatedAt(now);
            }
        }

        recomputeSharePercents(assets);
        repository.saveAll(assets);

        return new PriceRefreshResponse(true, assets.stream()
                .map(PortfolioAssetResponse::from)
                .toList());
    }

    private void fetchTwelveDataPrices(List<PortfolioAsset> assets,
                                        Map<String, BigDecimal> usdPrices,
                                        Map<String, BigDecimal> plnPrices) {
        // Crypto tickers use TICKER/USD pair format; stocks use ticker as-is
        Map<String, Map<String, String>> batchBody = new LinkedHashMap<>();
        for (PortfolioAsset asset : assets) {
            String ticker = asset.getTicker();
            boolean isCrypto = coinGeckoIdMapper.toId(ticker).isPresent();
            String symbol = isCrypto ? ticker + "/USD" : ticker;
            batchBody.put(ticker, Map.of("url", "/price?symbol=" + symbol + "&apikey=" + twelveDataApiKey));
        }
        batchBody.put("_USD_PLN", Map.of("url", "/price?symbol=USD/PLN&apikey=" + twelveDataApiKey));

        RestClient client = restClientBuilder.clone().baseUrl(twelveDataBaseUrl).build();

        try {
            Map<String, Object> batchResponse = client.post()
                    .uri("/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(batchBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (batchResponse == null) {
                return;
            }

            if (!(batchResponse.get("data") instanceof Map<?, ?> data)) {
                log.warn("Twelve Data batch response missing 'data' field");
                return;
            }

            BigDecimal usdPlnRate = null;
            if (data.get("_USD_PLN") instanceof Map<?, ?> rateEntry
                    && "success".equals(rateEntry.get("status"))
                    && rateEntry.get("response") instanceof Map<?, ?> ratePriceObj) {
                usdPlnRate = parseTwelveDataPrice(ratePriceObj.get("price")).orElse(null);
            }
            if (usdPlnRate == null) {
                log.warn("USD/PLN rate unavailable in Twelve Data batch; current_price_pln will be null for non-GPW assets");
            } else {
                log.info("USD/PLN rate from Twelve Data batch: {}", usdPlnRate);
            }

            for (PortfolioAsset asset : assets) {
                String ticker = asset.getTicker();
                if (!(data.get(ticker) instanceof Map<?, ?> entry)) {
                    log.warn("Twelve Data batch returned no entry for ticker={}", ticker);
                    continue;
                }
                if (!"success".equals(entry.get("status"))) {
                    log.warn("Twelve Data batch error for ticker={}: {}", ticker, entry.get("response"));
                    continue;
                }
                if (entry.get("response") instanceof Map<?, ?> priceObj) {
                    final BigDecimal rate = usdPlnRate;
                    parseTwelveDataPrice(priceObj.get("price")).ifPresent(p -> {
                        usdPrices.put(ticker, p);
                        if (rate != null) {
                            plnPrices.put(ticker, p.multiply(rate).setScale(4, RoundingMode.HALF_UP));
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Twelve Data price fetch failed: {}", e.getMessage());
        }
    }

    // Yahoo Finance blocks default Java User-Agent with 429; a browser UA is required
    private static final String YAHOO_FINANCE_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64)";

    private void fetchYahooFinancePrices(List<PortfolioAsset> gpwAssets,
                                          Map<String, BigDecimal> plnPrices) {
        RestClient client = restClientBuilder.clone().baseUrl(yahooFinanceBaseUrl).build();
        for (PortfolioAsset asset : gpwAssets) {
            String ticker = asset.getTicker();
            // PKN/XWAR → PKN.WA
            String yahooSymbol = ticker.substring(0, ticker.indexOf('/')) + ".WA";
            try {
                Map<String, Object> response = client.get()
                        .uri("/v8/finance/chart/" + yahooSymbol + "?interval=1d&range=1d")
                        .header("User-Agent", YAHOO_FINANCE_USER_AGENT)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (response == null) {
                    log.warn("Yahoo Finance returned null for ticker={}", ticker);
                    continue;
                }
                parseYahooFinancePrice(response).ifPresentOrElse(
                        p -> plnPrices.put(ticker, p),
                        () -> log.warn("Yahoo Finance returned no price for ticker={}", ticker));
            } catch (Exception e) {
                log.warn("Yahoo Finance price fetch failed for ticker={}: {}", ticker, e.getMessage());
            }
        }
    }

    private Optional<BigDecimal> parseYahooFinancePrice(Map<String, Object> response) {
        if (!(response.get("chart") instanceof Map<?, ?> chart)) {
            return Optional.empty();
        }
        if (!(chart.get("result") instanceof List<?> results) || results.isEmpty()) {
            return Optional.empty();
        }
        if (!(results.getFirst() instanceof Map<?, ?> result)) {
            return Optional.empty();
        }
        if (!(result.get("meta") instanceof Map<?, ?> meta)) {
            return Optional.empty();
        }
        if (meta.get("regularMarketPrice") instanceof Number price) {
            return Optional.of(BigDecimal.valueOf(price.doubleValue()));
        }
        return Optional.empty();
    }

    private Optional<BigDecimal> parseTwelveDataPrice(Object priceObj) {
        if (priceObj == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(priceObj.toString()));
        } catch (NumberFormatException e) {
            log.warn("Could not parse Twelve Data price: {}", priceObj);
            return Optional.empty();
        }
    }

    private void recomputeSharePercents(List<PortfolioAsset> assets) {
        BigDecimal totalValue = assets.stream()
                .filter(a -> a.getCurrentPricePln() != null)
                .map(a -> a.getShares().multiply(a.getCurrentPricePln()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalValue.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        for (PortfolioAsset asset : assets) {
            if (asset.getCurrentPricePln() == null) {
                asset.setCurrentSharePercent(null);
            } else {
                BigDecimal currentValue = asset.getShares().multiply(asset.getCurrentPricePln());
                BigDecimal percent = currentValue
                        .divide(totalValue, 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);
                asset.setCurrentSharePercent(percent);
            }
        }
    }
}
