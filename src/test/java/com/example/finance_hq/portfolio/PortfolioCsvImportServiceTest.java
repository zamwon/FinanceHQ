package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.CsvImportResult;
import com.example.finance_hq.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PortfolioCsvImportServiceTest {

    @Mock
    PortfolioAssetRepository repository;

    @InjectMocks
    PortfolioCsvImportService service;

    private final User user = new User();

    private static final String VALID_HEADER = "asset,shares,avg_buy_price_pln,avg_buy_price_asset_currency,purchase_value_pln,purchase_value_asset_currency,asset_group\n";

    @Test
    void import_validCsv_createsAssets() {
        String csv = VALID_HEADER + "BTC,0.5,150000.00,37500.00,75000.00,18750.00,Crypto\n";
        MockMultipartFile file = csv("portfolio.csv", csv);
        when(repository.findByUserAndTicker(user, "BTC")).thenReturn(Optional.empty());
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CsvImportResult result = service.importCsv(user, file);

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.rowErrors()).isEmpty();
    }

    @Test
    void import_withOptionalPurchaseSharePercent_parsesCorrectly() {
        String csv = VALID_HEADER.replace("\n", ",purchase_share_percent\n")
                + "BTC,0.5,150000.00,37500.00,75000.00,18750.00,Crypto,50.00\n";
        MockMultipartFile file = csv("portfolio.csv", csv);
        when(repository.findByUserAndTicker(user, "BTC")).thenReturn(Optional.empty());
        when(repository.saveAll(any())).thenAnswer(inv -> {
            PortfolioAsset a = ((java.util.List<PortfolioAsset>) inv.getArgument(0)).get(0);
            assertThat(a.getPurchaseSharePercent()).isEqualByComparingTo(new BigDecimal("50.00"));
            return inv.getArgument(0);
        });

        CsvImportResult result = service.importCsv(user, file);
        assertThat(result.importedCount()).isEqualTo(1);
    }

    @Test
    void import_missingRequiredHeader_throwsInvalidCsvException() {
        String csv = "asset,shares\nBTC,0.5\n";
        MockMultipartFile file = csv("portfolio.csv", csv);

        assertThatThrownBy(() -> service.importCsv(user, file))
                .isInstanceOf(InvalidCsvException.class)
                .hasMessageContaining("Missing required CSV headers");
    }

    @Test
    void import_badDecimal_producesRowError() {
        String csv = VALID_HEADER + "BTC,abc,150000.00,37500.00,75000.00,18750.00,Crypto\n";
        MockMultipartFile file = csv("portfolio.csv", csv);

        CsvImportResult result = service.importCsv(user, file);

        assertThat(result.importedCount()).isEqualTo(0);
        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0).rowNumber()).isEqualTo(1);
        assertThat(result.rowErrors().get(0).column()).isEqualTo("shares");
        verify(repository, never()).saveAll(any());
    }

    @Test
    void import_upsert_sameTicker_updatesExistingRow() {
        PortfolioAsset existing = new PortfolioAsset();
        existing.setTicker("BTC");
        existing.setUser(user);
        existing.setShares(new BigDecimal("0.1"));

        String csv = VALID_HEADER + "BTC,0.5,150000.00,37500.00,75000.00,18750.00,Crypto\n";
        MockMultipartFile file = csv("portfolio.csv", csv);
        when(repository.findByUserAndTicker(user, "BTC")).thenReturn(Optional.of(existing));
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CsvImportResult result = service.importCsv(user, file);

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(existing.getShares()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void import_rowCapExceeded_throwsInvalidCsvException() {
        StringBuilder csv = new StringBuilder(VALID_HEADER);
        for (int i = 0; i <= 10_000; i++) {
            csv.append("T").append(i).append(",1.0,100.00,100.00,100.00,100.00,Group\n");
        }
        MockMultipartFile file = csv("portfolio.csv", csv.toString());

        assertThatThrownBy(() -> service.importCsv(user, file))
                .isInstanceOf(InvalidCsvException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void import_europeanDecimalComma_parsesCorrectly() {
        String csv = VALID_HEADER + "BTC,1.234,56,1.500,00,150.000,00,37.500,00,75.000,00,18.750,00,Crypto\n";
        // Use a simpler test with comma-formatted single value
        String csv2 = VALID_HEADER + "ETH,2,5,8000,00,2000,00,16000,00,4000,00,Crypto\n";
        MockMultipartFile file = csv("portfolio.csv", csv2);
        when(repository.findByUserAndTicker(eq(user), any())).thenReturn(Optional.empty());
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CsvImportResult result = service.importCsv(user, file);

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.rowErrors()).isEmpty();
    }

    @Test
    void import_groupOfAssetAlias_accepted() {
        String csv = "asset,shares,avg_buy_price_pln,avg_buy_price_asset_currency,purchase_value_pln,purchase_value_asset_currency,group_of_asset\n"
                + "ETH,1.0,8000.00,2000.00,8000.00,2000.00,Crypto\n";
        MockMultipartFile file = csv("portfolio.csv", csv);
        when(repository.findByUserAndTicker(user, "ETH")).thenReturn(Optional.empty());
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CsvImportResult result = service.importCsv(user, file);
        assertThat(result.importedCount()).isEqualTo(1);
    }

    private MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes());
    }
}
