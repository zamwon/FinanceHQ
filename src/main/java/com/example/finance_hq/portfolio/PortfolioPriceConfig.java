package com.example.finance_hq.portfolio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PortfolioPriceConfig {

    @Bean
    RestClient.Builder portfolioRestClientBuilder() {
        return RestClient.builder();
    }
}
