package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.CreatePortfolioAssetRequest;
import com.example.finance_hq.portfolio.dto.CsvImportResult;
import com.example.finance_hq.portfolio.dto.PortfolioAssetResponse;
import com.example.finance_hq.portfolio.dto.UpdatePortfolioAssetRequest;
import com.example.finance_hq.user.User;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.finance_hq.util.LogMaskingUtils.maskEmail;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

@Slf4j
@RestController
@RequestMapping(PortfolioAssetController.BASE_PATH)
public class PortfolioAssetController {

    public static final String BASE_PATH = "/api/portfolio";

    private final PortfolioAssetService service;
    private final PortfolioCsvImportService csvImportService;

    public PortfolioAssetController(PortfolioAssetService service, PortfolioCsvImportService csvImportService) {
        this.service = service;
        this.csvImportService = csvImportService;
    }

    @GetMapping
    public ResponseEntity<List<PortfolioAssetResponse>> list(@AuthenticationPrincipal User user) {
        log.info("Started list portfolio assets as {}", maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.findAll(user));
    }

    @PostMapping
    public ResponseEntity<PortfolioAssetResponse> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreatePortfolioAssetRequest req) {
        log.info("Started create portfolio asset as {}", maskEmail(user.getEmail()));
        return ResponseEntity.status(201).body(service.create(user, req));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PortfolioAssetResponse> update(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePortfolioAssetRequest req) {
        log.info("Started update portfolio asset {} as {}", id, maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.update(user, id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        log.info("Started delete portfolio asset {} as {}", id, maskEmail(user.getEmail()));
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/import", consumes = MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importCsv(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file) {
        log.info("Started import CSV as {}", maskEmail(user.getEmail()));
        // UX guard only — client-declared content-type is not a security boundary;
        // Commons CSV is the real gate (non-text content will fail to parse → 400).
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("text/csv")
                && !contentType.equals("text/plain")
                && !contentType.equals("application/csv"))) {
            ProblemDetail problem = ProblemDetail.forStatus(400);
            problem.setTitle("Bad Request");
            problem.setDetail("Only CSV files are accepted (text/csv, text/plain, application/csv)");
            return ResponseEntity.status(400).body(problem);
        }
        if (file.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatus(400);
            problem.setTitle("Bad Request");
            problem.setDetail("Uploaded file is empty");
            return ResponseEntity.status(400).body(problem);
        }

        CsvImportResult result = csvImportService.importCsv(user, file);

        if (!result.rowErrors().isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatus(422);
            problem.setTitle("Unprocessable Entity");
            problem.setDetail("CSV contains validation errors");
            problem.setProperty("importedCount", result.importedCount());
            problem.setProperty("rowErrors", result.rowErrors());
            return ResponseEntity.status(422).body(problem);
        }

        return ResponseEntity.ok(Map.of("importedCount", result.importedCount()));
    }
}
