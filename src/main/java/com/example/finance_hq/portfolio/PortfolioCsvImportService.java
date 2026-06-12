package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.CsvImportResult;
import com.example.finance_hq.portfolio.dto.RowError;
import com.example.finance_hq.user.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PortfolioCsvImportService {

    private static final int ROW_LIMIT = 10_000;

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

    private final PortfolioAssetRepository repository;

    public PortfolioCsvImportService(PortfolioAssetRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CsvImportResult importCsv(User user, MultipartFile file) {
        List<CSVRecord> records;
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            records = CSV_FORMAT.parse(reader).getRecords();
        } catch (Exception e) {
            throw new InvalidCsvException("Could not read CSV file: " + e.getMessage());
        }

        if (records.isEmpty()) {
            return new CsvImportResult(0, List.of());
        }

        CSVRecord first = records.getFirst();
        validateRequiredHeaders(first);

        if (records.size() > ROW_LIMIT) {
            throw new InvalidCsvException("CSV exceeds the maximum of " + ROW_LIMIT + " rows");
        }

        List<RowError> errors = new ArrayList<>();
        List<PortfolioAsset> entities = new ArrayList<>();

        int rowNum = 1;
        for (CSVRecord record : records) {
            if (record.values().length == 0) {
                rowNum++;
                continue;
            }

            String ticker = record.get("asset").trim();
            if (ticker.isBlank()) {
                errors.add(new RowError(rowNum, "asset", "Asset ticker must not be blank"));
                rowNum++;
                continue;
            }

            String assetGroup = resolveAssetGroup(record);

            BigDecimal shares = parseBigDecimal(record, "shares", rowNum, errors);
            BigDecimal avgBuyPricePln = parseBigDecimal(record, "avg_buy_price_pln", rowNum, errors);
            BigDecimal avgBuyPriceAssetCurrency = parseBigDecimal(record, "avg_buy_price_asset_currency", rowNum, errors);
            BigDecimal purchaseValuePln = parseBigDecimal(record, "purchase_value_pln", rowNum, errors);
            BigDecimal purchaseValueAssetCurrency = parseBigDecimal(record, "purchase_value_asset_currency", rowNum, errors);

            BigDecimal purchaseSharePercent = null;
            if (record.isMapped("purchase_share_percent")) {
                String raw = record.get("purchase_share_percent").trim();
                if (!raw.isBlank()) {
                    purchaseSharePercent = parseBigDecimalRaw(raw, "purchase_share_percent", rowNum, errors);
                }
            }

            if (shares != null && avgBuyPricePln != null && avgBuyPriceAssetCurrency != null
                    && purchaseValuePln != null && purchaseValueAssetCurrency != null) {
                PortfolioAsset entity = repository.findByUserAndTicker(user, ticker)
                        .orElseGet(() -> {
                            PortfolioAsset a = new PortfolioAsset();
                            a.setUser(user);
                            a.setTicker(ticker);
                            return a;
                        });
                entity.setAssetGroup(assetGroup);
                entity.setShares(shares);
                entity.setAvgBuyPricePln(avgBuyPricePln);
                entity.setAvgBuyPriceAssetCurrency(avgBuyPriceAssetCurrency);
                entity.setPurchaseValuePln(purchaseValuePln);
                entity.setPurchaseValueAssetCurrency(purchaseValueAssetCurrency);
                entity.setPurchaseSharePercent(purchaseSharePercent);
                entities.add(entity);
            }

            rowNum++;
        }

        if (!errors.isEmpty()) {
            return new CsvImportResult(0, errors);
        }

        repository.saveAll(entities);
        return new CsvImportResult(entities.size(), List.of());
    }

    private void validateRequiredHeaders(CSVRecord first) {
        List<String> required = List.of(
                "asset", "shares", "avg_buy_price_pln", "avg_buy_price_asset_currency",
                "purchase_value_pln", "purchase_value_asset_currency"
        );
        List<String> missing = required.stream()
                .filter(h -> !first.isMapped(h))
                .toList();
        if (!missing.isEmpty()) {
            throw new InvalidCsvException("Missing required CSV headers: " + missing);
        }
        if (!first.isMapped("asset_group") && !first.isMapped("group_of_asset")) {
            throw new InvalidCsvException("Missing required CSV headers: [asset_group (or group_of_asset)]");
        }
    }

    private String resolveAssetGroup(CSVRecord record) {
        if (record.isMapped("asset_group")) {
            return record.get("asset_group").trim();
        }
        return record.get("group_of_asset").trim();
    }

    private BigDecimal parseBigDecimal(CSVRecord record, String column, int rowNum, List<RowError> errors) {
        String raw = record.get(column).trim();
        return parseBigDecimalRaw(raw, column, rowNum, errors);
    }

    private BigDecimal parseBigDecimalRaw(String raw, String column, int rowNum, List<RowError> errors) {
        try {
            return new BigDecimal(raw.replace(",", "."));
        } catch (NumberFormatException e) {
            errors.add(new RowError(rowNum, column, "Invalid number: \"" + raw + "\""));
            return null;
        }
    }
}
