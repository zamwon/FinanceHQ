package com.example.finance_hq.portfolio;

import com.example.finance_hq.TestcontainersConfiguration;
import com.example.finance_hq.portfolio.dto.PortfolioAssetResponse;
import com.example.finance_hq.portfolio.dto.PriceRefreshResponse;
import com.example.finance_hq.user.User;
import com.example.finance_hq.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class PortfolioPriceServiceTest {

    @Autowired
    PortfolioPriceService service;

    @Autowired
    PortfolioAssetRepository assetRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    @Qualifier("portfolioRestClientBuilder")
    RestClient.Builder portfolioRestClientBuilder;

    MockRestServiceServer priceApiServer;

    @BeforeEach
    void setUp() {
        priceApiServer = MockRestServiceServer.bindTo(portfolioRestClientBuilder)
                .ignoreExpectOrder(true)
                .build();
    }

    @Test
    void cryptoOnly_callsTwelveData() {
        User user = createUser("price_crypto@test.com");
        createAsset(user, "BTC", "Crypto", 1.0);
        createAsset(user, "ETH", "Crypto", 2.0);

        priceApiServer.expect(requestTo(containsString("twelvedata")))
                      .andRespond(withSuccess(
                              "{\"code\":200,\"status\":\"success\",\"data\":{" +
                              "\"BTC\":{\"response\":{\"price\":\"65000.00\"},\"status\":\"success\"}," +
                              "\"ETH\":{\"response\":{\"price\":\"3500.00\"},\"status\":\"success\"}," +
                              "\"_USD_PLN\":{\"response\":{\"price\":\"4.00\"},\"status\":\"success\"}}}",
                              MediaType.APPLICATION_JSON));

        PriceRefreshResponse result = service.refreshIfStale(user);

        priceApiServer.verify();
        assertThat(result.refreshed()).isTrue();
        assertThat(result.assets()).hasSize(2);
        assertThat(findByTicker(result, "BTC").currentPriceUsd()).isEqualByComparingTo("65000.00");
        assertThat(findByTicker(result, "BTC").currentPricePln()).isEqualByComparingTo("260000.0000");
    }

    @Test
    void securitiesOnly_callsTwelveDataOnly_notCoinGecko() {
        User user = createUser("price_sec@test.com");
        createAsset(user, "AAPL", "US Stocks", 10.0);
        createAsset(user, "MSFT", "US Stocks", 5.0);

        priceApiServer.expect(requestTo(containsString("twelvedata")))
                      .andRespond(withSuccess(
                              "{\"code\":200,\"status\":\"success\",\"data\":{" +
                              "\"AAPL\":{\"response\":{\"price\":\"200.00\"},\"status\":\"success\"}," +
                              "\"MSFT\":{\"response\":{\"price\":\"350.00\"},\"status\":\"success\"}," +
                              "\"_USD_PLN\":{\"response\":{\"price\":\"4.00\"},\"status\":\"success\"}}}",
                              MediaType.APPLICATION_JSON));

        PriceRefreshResponse result = service.refreshIfStale(user);

        priceApiServer.verify();
        assertThat(result.refreshed()).isTrue();
        assertThat(findByTicker(result, "AAPL").currentPriceUsd()).isEqualByComparingTo("200.00");
        assertThat(findByTicker(result, "AAPL").currentPricePln()).isEqualByComparingTo("800.0000");
        assertThat(findByTicker(result, "MSFT").currentPriceUsd()).isEqualByComparingTo("350.00");
        assertThat(findByTicker(result, "MSFT").currentPricePln()).isEqualByComparingTo("1400.0000");
    }

    @Test
    void mixedPortfolio_callsTwelveDataOnly() {
        User user = createUser("price_mixed@test.com");
        createAsset(user, "BTC", "Crypto", 1.0);
        createAsset(user, "AAPL", "US Stocks", 10.0);

        priceApiServer.expect(requestTo(containsString("twelvedata")))
                      .andRespond(withSuccess(
                              "{\"code\":200,\"status\":\"success\",\"data\":{" +
                              "\"BTC\":{\"response\":{\"price\":\"65000.00\"},\"status\":\"success\"}," +
                              "\"AAPL\":{\"response\":{\"price\":\"200.00\"},\"status\":\"success\"}," +
                              "\"_USD_PLN\":{\"response\":{\"price\":\"4.00\"},\"status\":\"success\"}}}",
                              MediaType.APPLICATION_JSON));

        PriceRefreshResponse result = service.refreshIfStale(user);

        priceApiServer.verify();
        assertThat(result.refreshed()).isTrue();
        assertThat(findByTicker(result, "BTC").currentPriceUsd()).isEqualByComparingTo("65000.00");
        assertThat(findByTicker(result, "BTC").currentPricePln()).isEqualByComparingTo("260000.0000");
        assertThat(findByTicker(result, "AAPL").currentPriceUsd()).isEqualByComparingTo("200.00");
        assertThat(findByTicker(result, "AAPL").currentPricePln()).isEqualByComparingTo("800.0000");
    }

    @Test
    void throttle_withinStaleThreshold_returnsFalse_noApiCalls() {
        User user = createUser("price_throttle@test.com");
        PortfolioAsset asset = createAsset(user, "BTC", "Crypto", 1.0);
        asset.setPriceLastUpdatedAt(Instant.now().minusSeconds(60));
        assetRepository.saveAndFlush(asset);

        PriceRefreshResponse result = service.refreshIfStale(user);

        assertThat(result.refreshed()).isFalse();
        priceApiServer.verify();
    }

    @Test
    void unknownTicker_otherAssetsStillUpdate() {
        User user = createUser("price_unknown@test.com");
        createAsset(user, "AAPL", "US Stocks", 10.0);
        createAsset(user, "UNKNOWN_XYZ", "Other", 100.0);

        // Batch response: AAPL succeeds, UNKNOWN_XYZ returns error — price stays null
        priceApiServer.expect(requestTo(containsString("twelvedata")))
                      .andRespond(withSuccess(
                              "{\"code\":200,\"status\":\"success\",\"data\":{" +
                              "\"AAPL\":{\"response\":{\"price\":\"200.00\"},\"status\":\"success\"}," +
                              "\"UNKNOWN_XYZ\":{\"response\":{\"code\":404,\"message\":\"Symbol not found\"},\"status\":\"error\"}," +
                              "\"_USD_PLN\":{\"response\":{\"price\":\"4.00\"},\"status\":\"success\"}}}",
                              MediaType.APPLICATION_JSON));

        PriceRefreshResponse result = service.refreshIfStale(user);

        priceApiServer.verify();
        assertThat(result.refreshed()).isTrue();
        assertThat(findByTicker(result, "AAPL").currentPriceUsd()).isEqualByComparingTo("200.00");
        assertThat(findByTicker(result, "AAPL").currentPricePln()).isEqualByComparingTo("800.0000");
        assertThat(findByTicker(result, "UNKNOWN_XYZ").currentPricePln()).isNull();
    }

    @Test
    void sharePercent_computedCorrectly_threeAssets() {
        // BTC: 1 share @ 400,000 PLN = 400,000; ETH: 1 share @ 40,000 PLN = 40,000;
        // AAPL: 1 share @ 4,000 PLN = 4,000; total PLN = 444,000 (USD × 4.00 rate)
        User user = createUser("price_share@test.com");
        createAsset(user, "BTC", "Crypto", 1.0);
        createAsset(user, "ETH", "Crypto", 1.0);
        createAsset(user, "AAPL", "US Stocks", 1.0);

        priceApiServer.expect(requestTo(containsString("twelvedata")))
                      .andRespond(withSuccess(
                              "{\"code\":200,\"status\":\"success\",\"data\":{" +
                              "\"BTC\":{\"response\":{\"price\":\"100000.00\"},\"status\":\"success\"}," +
                              "\"ETH\":{\"response\":{\"price\":\"10000.00\"},\"status\":\"success\"}," +
                              "\"AAPL\":{\"response\":{\"price\":\"1000.00\"},\"status\":\"success\"}," +
                              "\"_USD_PLN\":{\"response\":{\"price\":\"4.00\"},\"status\":\"success\"}}}",
                              MediaType.APPLICATION_JSON));

        PriceRefreshResponse result = service.refreshIfStale(user);

        // 100000/111000*100 = 90.0901, 10000/111000*100 = 9.0090, 1000/111000*100 = 0.9009
        assertThat(findByTicker(result, "BTC").currentSharePercent())
                .isEqualByComparingTo("90.0901");
        assertThat(findByTicker(result, "ETH").currentSharePercent())
                .isEqualByComparingTo("9.0090");
        assertThat(findByTicker(result, "AAPL").currentSharePercent())
                .isEqualByComparingTo("0.9009");
    }

    @Test
    void twelveDataRateMissing_populatesUsdOnly() {
        User user = createUser("price_rate_missing@test.com");
        createAsset(user, "AAPL", "US Stocks", 10.0);

        priceApiServer.expect(requestTo(containsString("twelvedata")))
                      .andRespond(withSuccess(
                              "{\"code\":200,\"status\":\"success\",\"data\":{" +
                              "\"AAPL\":{\"response\":{\"price\":\"200.00\"},\"status\":\"success\"}," +
                              "\"_USD_PLN\":{\"response\":{\"code\":500,\"message\":\"Error\"},\"status\":\"error\"}}}",
                              MediaType.APPLICATION_JSON));

        PriceRefreshResponse result = service.refreshIfStale(user);

        priceApiServer.verify();
        assertThat(result.refreshed()).isTrue();
        assertThat(findByTicker(result, "AAPL").currentPriceUsd()).isEqualByComparingTo("200.00");
        assertThat(findByTicker(result, "AAPL").currentPricePln()).isNull();
    }

    @Test
    void gpwStocks_callYahooFinance_notTwelveData() {
        User user = createUser("price_gpw@test.com");
        createAsset(user, "PKN/XWAR", "PL Stocks", 50.0);

        priceApiServer.expect(requestTo(containsString("finance.yahoo.com")))
                      .andRespond(withSuccess(
                              "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":145.88," +
                              "\"currency\":\"PLN\",\"symbol\":\"PKN.WA\"}}],\"error\":null}}",
                              MediaType.APPLICATION_JSON));

        PriceRefreshResponse result = service.refreshIfStale(user);

        priceApiServer.verify();
        assertThat(result.refreshed()).isTrue();
        assertThat(findByTicker(result, "PKN/XWAR").currentPricePln())
                .isEqualByComparingTo("145.88");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private User createUser(String email) {
        User user = new User(email, passwordEncoder.encode("Test1234!"));
        return userRepository.saveAndFlush(user);
    }

    private PortfolioAsset createAsset(User user, String ticker, String group, double shares) {
        PortfolioAsset asset = new PortfolioAsset();
        asset.setUser(user);
        asset.setTicker(ticker);
        asset.setAssetGroup(group);
        asset.setShares(BigDecimal.valueOf(shares));
        asset.setAvgBuyPricePln(BigDecimal.valueOf(100));
        asset.setAvgBuyPriceAssetCurrency(BigDecimal.valueOf(100));
        asset.setPurchaseValuePln(BigDecimal.valueOf(100).multiply(BigDecimal.valueOf(shares)));
        asset.setPurchaseValueAssetCurrency(BigDecimal.valueOf(100).multiply(BigDecimal.valueOf(shares)));
        return assetRepository.saveAndFlush(asset);
    }

    private PortfolioAssetResponse findByTicker(PriceRefreshResponse result, String ticker) {
        return result.assets().stream()
                .filter(a -> a.ticker().equals(ticker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Asset not found: " + ticker));
    }
}
