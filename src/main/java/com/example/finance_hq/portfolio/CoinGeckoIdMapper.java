package com.example.finance_hq.portfolio;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class CoinGeckoIdMapper {

    private static final Map<String, String> COIN_IDS = Map.ofEntries(
            Map.entry("BTC", "bitcoin"),
            Map.entry("ETH", "ethereum"),
            Map.entry("SOL", "solana"),
            Map.entry("BNB", "binancecoin"),
            Map.entry("XRP", "ripple"),
            Map.entry("ADA", "cardano"),
            Map.entry("AVAX", "avalanche-2"),
            Map.entry("DOGE", "dogecoin"),
            Map.entry("DOT", "polkadot"),
            Map.entry("MATIC", "matic-network"),
            Map.entry("LINK", "chainlink"),
            Map.entry("UNI", "uniswap"),
            Map.entry("LTC", "litecoin"),
            Map.entry("ATOM", "cosmos"),
            Map.entry("FTM", "fantom"),
            Map.entry("NEAR", "near"),
            Map.entry("ALGO", "algorand"),
            Map.entry("VET", "vechain"),
            Map.entry("ICP", "internet-computer"),
            Map.entry("FIL", "filecoin"),
            Map.entry("SHIB", "shiba-inu")
    );

    public Optional<String> toId(String ticker) {
        String base = ticker.contains("/") ? ticker.substring(0, ticker.indexOf('/')) : ticker;
        return Optional.ofNullable(COIN_IDS.get(base.toUpperCase()));
    }
}
