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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PortfolioCsvImportService {

    private static final int ROW_LIMIT = 10_000;

    // Normalized form → canonical column name.
    // Normalize: lowercase → strip [^a-z0-9 ] → collapse spaces → trim.
    // Stripping all non-ASCII characters makes this encoding-agnostic: garbled Polish
    // chars (CP1250 bytes read as UTF-8, or Latin-1 surrogates like æ, ³) all strip
    // to the same ASCII skeleton, matching the same normalized key as correct UTF-8.
    // Example: "Wartość" and "Wartoæ" both normalize to "warto zakupu pln".
    private static final Map<String, String> NORMALIZED_ALIASES = Map.ofEntries(
            Map.entry("walor", "asset"),
            Map.entry("liczba jednostek", "shares"),
            Map.entry("śr. cena zakupu pln", "avg_buy_price_pln"),
            Map.entry("śr. cena zakupu w walucie waloru", "avg_buy_price_asset_currency"),
            Map.entry("wartość zakupu pln", "purchase_value_pln"),
            Map.entry("wartość zakupu w walucie waloru", "purchase_value_asset_currency"),
            Map.entry("udział w wartości zakupu", "purchase_share_percent"),
            Map.entry("cena aktualna pln", "current_price"),
            Map.entry("grupa", "asset_group"),
            Map.entry("group of asset", "asset_group")
    );

    private final PortfolioAssetRepository repository;

    public PortfolioCsvImportService(PortfolioAssetRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CsvImportResult importCsv(User user, MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new InvalidCsvException("Could not read CSV file: " + e.getMessage());
        }

        String headerLine = readFirstLine(bytes);
        char delimiter = headerLine.contains(";") ? ';' : ',';
        String[] rawHeaders = headerLine.split(Pattern.quote(String.valueOf(delimiter)), -1);
        String[] canonicalHeaders = remapHeaders(rawHeaders);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(canonicalHeaders)
                .setSkipHeaderRecord(true)
                .setDelimiter(delimiter)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();

        List<CSVRecord> records;
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), Charset.forName("windows-1252"))) {
            records = format.parse(reader).getRecords();
        } catch (Exception e) {
            throw new InvalidCsvException("Could not parse CSV file: " + e.getMessage());
        }

        if (records.isEmpty()) {
            return new CsvImportResult(0, List.of());
        }

        validateRequiredHeaders(records.getFirst());

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
                rowNum++;
                continue; // skip summary/total rows with no ticker
            }

            String assetGroup = resolveAssetGroup(record);
            if (assetGroup.isBlank()) {
                assetGroup = "Other";
            }

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
                if (record.isMapped("purchase_share_percent")) {
                    entity.setPurchaseSharePercent(purchaseSharePercent);
                }
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

    private String readFirstLine(byte[] bytes) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), Charset.forName("windows-1250")))) {
            String line = br.readLine();
            return line != null ? line : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String[] remapHeaders(String[] rawHeaders) {
        // Pre-normalize the alias map keys so they match regardless of encoding
        Map<String, String> normalizedLookup = new java.util.HashMap<>();
        NORMALIZED_ALIASES.forEach((k, v) -> normalizedLookup.put(normalize(k), v));

        String[] canonical = new String[rawHeaders.length];
        for (int i = 0; i < rawHeaders.length; i++) {
            String raw = rawHeaders[i].trim();
            if (raw.isEmpty()) {
                canonical[i] = "_col_" + i + "_";
                continue;
            }
            String key = normalize(raw);
            canonical[i] = normalizedLookup.getOrDefault(key, raw.toLowerCase(Locale.ROOT));
        }
        return canonical;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll(" +", " ")
                .trim();
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
        if (raw.isEmpty() || raw.equals("-") || raw.equals("---") || raw.equalsIgnoreCase("n/a")) {
            return null; // placeholder value — silently skip, no error
        }
        try {
            return new BigDecimal(normalizeNumber(raw));
        } catch (NumberFormatException e) {
            errors.add(new RowError(rowNum, column, "Invalid number: \"" + raw + "\""));
            return null;
        }
    }

    // Handles European number formats: "1 234,56", "1.234,56", "1,5", "1.5"
    private static String normalizeNumber(String raw) {
        raw = raw.replace(" ", "").replace(" ", ""); // strip space thousand separators
        if (raw.contains(".") && raw.contains(",")) {
            // European format: dot = thousand sep, comma = decimal sep
            return raw.replace(".", "").replace(",", ".");
        }
        return raw.replace(",", ".");
    }
}
