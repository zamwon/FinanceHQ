package com.example.finance_hq.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
@ActiveProfiles("test")
class SentryTunnelControllerIntegrationTest {

	@Autowired
	private WebApplicationContext webApplicationContext;

	private MockMvc mockMvc;
	private ObjectMapper objectMapper;

	private String validDsn;
	private String validEnvelope;

	@BeforeEach
	void setup() throws Exception {
		mockMvc = MockMvcBuilders
			.webAppContextSetup(webApplicationContext)
			.apply(springSecurity())
			.build();

		objectMapper = new ObjectMapper();
		validDsn = "https://1438eaed05cfed18f8346d4ec2e1b219@o4511530249945088.ingest.de.sentry.io/4511530295361616";

		// Construct a valid Sentry envelope with the known DSN
		Map<String, Object> header = new HashMap<>();
		header.put("dsn", validDsn);
		String headerJson = objectMapper.writeValueAsString(header);

		// Envelope format: header line + event JSON line(s)
		Map<String, Object> event = new HashMap<>();
		event.put("message", "test event");
		event.put("level", "error");
		String eventJson = objectMapper.writeValueAsString(event);

		validEnvelope = headerJson + "\n" + eventJson;
	}

	@Test
	void testTunnelWithEmptyBody_Returns400() throws Exception {
		mockMvc.perform(post("/sentry-tunnel")
				.contentType(MediaType.TEXT_PLAIN)
				.content(""))
			.andExpect(status().is(400));
	}

	@Test
	void testTunnelWithMissingDsn_Returns400() throws Exception {
		// Envelope header without DSN field
		Map<String, Object> header = new HashMap<>();
		String headerJson = objectMapper.writeValueAsString(header);

		String envelope = headerJson + "\n{}";

		mockMvc.perform(post("/sentry-tunnel")
				.contentType(MediaType.TEXT_PLAIN)
				.content(envelope))
			.andExpect(status().is(400));
	}

	@Test
	void testTunnelWithUnknownDsn_Returns400() throws Exception {
		// Create envelope with unknown DSN
		Map<String, Object> header = new HashMap<>();
		header.put("dsn", "https://unknown@sentry.io/999999");
		String headerJson = objectMapper.writeValueAsString(header);

		Map<String, Object> event = new HashMap<>();
		event.put("message", "test");
		String eventJson = objectMapper.writeValueAsString(event);

		String envelope = headerJson + "\n" + eventJson;

		mockMvc.perform(post("/sentry-tunnel")
				.contentType(MediaType.TEXT_PLAIN)
				.content(envelope))
			.andExpect(status().is(400));
	}

	@Test
	void testTunnelRateLimiting_Returns429OnExceed() throws Exception {
		// Send 101 requests rapidly
		for (int i = 0; i < 100; i++) {
			mockMvc.perform(post("/sentry-tunnel")
					.contentType(MediaType.TEXT_PLAIN)
					.content(validEnvelope))
				.andExpect(status().isOk());
		}

		// 101st request should be rate limited
		mockMvc.perform(post("/sentry-tunnel")
				.contentType(MediaType.TEXT_PLAIN)
				.content(validEnvelope))
			.andExpect(status().is(429));
	}
}
