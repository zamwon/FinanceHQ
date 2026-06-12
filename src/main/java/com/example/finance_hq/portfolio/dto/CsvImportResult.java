package com.example.finance_hq.portfolio.dto;

import java.util.List;

public record CsvImportResult(int importedCount, List<RowError> rowErrors) {
}
