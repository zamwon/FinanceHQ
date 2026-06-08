package com.example.finance_hq.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/sentry-tunnel")
public class SentryTunnelController {

	private static final String FRONTEND_DSN = "https://1438eaed05cfed18f8346d4ec2e1b219@o4511530249945088.ingest.de.sentry.io/4511530295361616";
	private static final Set<String> DSN_ALLOW_LIST = Set.of(FRONTEND_DSN);
	private static final String SENTRY_INGEST_URL = "https://o4511530249945088.ingest.de.sentry.io/api/{projectId}/envelope/";
	private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("/(\\d+)$");
	private static final long RATE_LIMIT_WINDOW_MS = 60_000;
	private static final int MAX_REQUESTS_PER_MINUTE = 100;

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;
	private final AtomicLong requestCount = new AtomicLong(0);
	private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

	public SentryTunnelController(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
		this.objectMapper = new ObjectMapper();
	}

	@PostMapping
	public ResponseEntity<Void> tunnel(@RequestBody(required = false) String body) {
		if (body == null || body.trim().isEmpty()) {
			log.warn("Invalid Sentry envelope: empty body");
			return ResponseEntity.badRequest().build();
		}

		// Check rate limit
		long now = System.currentTimeMillis();
		long currentWindowStart = windowStart.get();

		if (now - currentWindowStart >= RATE_LIMIT_WINDOW_MS) {
			// Window expired, reset
			windowStart.set(now);
			requestCount.set(1);
		} else {
			// Within window, increment and check limit
			long count = requestCount.incrementAndGet();
			if (count > MAX_REQUESTS_PER_MINUTE) {
				log.warn("Rate limit exceeded: {} requests in current minute", count);
				return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
			}
		}

		// Parse first line to extract DSN
		String dsn = extractDsnFromEnvelope(body);
		if (dsn == null || dsn.isEmpty()) {
			log.warn("Invalid Sentry envelope: missing or empty DSN");
			return ResponseEntity.badRequest().build();
		}

		// Validate DSN against allow-list
		if (!DSN_ALLOW_LIST.contains(dsn)) {
			log.warn("Unknown DSN in Sentry envelope: {}", maskDsn(dsn));
			return ResponseEntity.badRequest().build();
		}

		// Extract project ID from DSN
		String projectId = extractProjectIdFromDsn(dsn);
		if (projectId == null || projectId.isEmpty()) {
			log.warn("Could not extract project ID from DSN: {}", maskDsn(dsn));
			return ResponseEntity.badRequest().build();
		}

		// Forward to Sentry
		String sentryUrl = SENTRY_INGEST_URL.replace("{projectId}", projectId);
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.set(HttpHeaders.CONTENT_TYPE, "application/x-sentry-envelope");
			HttpEntity<String> request = new HttpEntity<>(body, headers);

			ResponseEntity<Void> response = restTemplate.postForEntity(sentryUrl, request, Void.class);

			if (!response.getStatusCode().is2xxSuccessful()) {
				log.warn("Sentry API returned {}", response.getStatusCode());
			}

			return ResponseEntity.ok().build();
		} catch (RestClientException e) {
			log.warn("Failed to proxy envelope to Sentry", e);
			return ResponseEntity.ok().build();
		}
	}

	public void resetRateLimiter() {
		requestCount.set(0);
		windowStart.set(System.currentTimeMillis());
	}

	private String extractDsnFromEnvelope(String body) {
		if (body == null || body.isEmpty()) {
			return null;
		}

		// Sentry envelope format: first line is a JSON header
		int newlineIndex = body.indexOf('\n');
		if (newlineIndex <= 0) {
			return null;
		}

		String headerLine = body.substring(0, newlineIndex);
		try {
			JsonNode header = objectMapper.readTree(headerLine);
			JsonNode dsnNode = header.get("dsn");
			return dsnNode != null ? dsnNode.asText() : null;
		} catch (Exception e) {
			log.debug("Failed to parse Sentry envelope header", e);
			return null;
		}
	}

	private String extractProjectIdFromDsn(String dsn) {
		// DSN format: https://key@ingest.de.sentry.io/projectId
		// Extract the project ID from the last path segment
		Matcher matcher = PROJECT_ID_PATTERN.matcher(dsn);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	private String maskDsn(String dsn) {
		if (dsn == null || dsn.length() < 20) {
			return "[masked]";
		}
		return dsn.substring(0, 10) + "..." + dsn.substring(dsn.length() - 10);
	}
}
