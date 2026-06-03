package com.example.finance_hq.obligation;

import com.example.finance_hq.TestcontainersConfiguration;
import com.example.finance_hq.auth.dto.LoginRequest;
import com.example.finance_hq.auth.dto.RegisterRequest;
import com.example.finance_hq.user.User;
import com.example.finance_hq.user.UserRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class ObligationDataIntegrityTest {

    public static final String AUTH_REGISTER = "/auth/register";
    public static final String AUTH_LOGIN = "/auth/login";
    public static final String API_OBLIGATIONS = "/api/obligations";

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;
    @Autowired ObligationRepository obligationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    void fixedTerm_allFieldsRoundTripWithoutTruncation() throws Exception {
        String email = "integrity_roundtrip@test.com";
        String token = registerAndLogin(email, "Test1234!");
        User user = userRepository.findByEmail(email).orElseThrow();

        obligationRepository.saveAndFlush(new Obligation(
                user, "Loan Repayment", new BigDecimal("500.12"),
                ObligationCategory.IMPORTANT, ObligationPeriod.FIXED_TERM,
                31, LocalDate.of(2027, 3, 31), 12));

        MvcResult result = mvc.perform(get(API_OBLIGATIONS)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> body = parseBodyAsList(result);
        assertThat(body).hasSize(1);
        Map<String, Object> o = body.getFirst();

        assertThat(o.get("name")).isEqualTo("Loan Repayment");
        assertThat(((BigDecimal) o.get("amount")).compareTo(new BigDecimal("500.12"))).isZero();
        assertThat(o.get("category")).isEqualTo("IMPORTANT");
        assertThat(o.get("period")).isEqualTo("FIXED_TERM");
        assertThat(o.get("paymentDay")).isEqualTo(31);
        assertThat(o.get("endDate")).isEqualTo("2027-03-31");
        assertThat(o.get("remainingPayments")).isEqualTo(12);
        assertThat(o.get("createdAt")).isNotNull();
    }

    @Test
    void patch_preservesAllUnmodifiedFields() throws Exception {
        String email = "integrity_patch@test.com";
        String token = registerAndLogin(email, "Test1234!");
        User user = userRepository.findByEmail(email).orElseThrow();

        MvcResult created = mvc.perform(post(API_OBLIGATIONS)
                        .contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(json(fixedTermBody())))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> before = parseBody(created);
        String id = (String) before.get("id");

        mvc.perform(patch(API_OBLIGATIONS + "/" + id)
                        .contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(json(Map.of("amount", 1234.56))))
                .andExpect(status().isOk());

        MvcResult listResult = mvc.perform(get(API_OBLIGATIONS)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> after = parseBodyAsList(listResult).stream()
                .filter(o -> id.equals(o.get("id")))
                .findFirst().orElseThrow();

        assertThat(after.get("name")).isEqualTo(before.get("name"));
        assertThat(after.get("category")).isEqualTo(before.get("category"));
        assertThat(after.get("period")).isEqualTo(before.get("period"));
        assertThat(after.get("paymentDay")).isEqualTo(before.get("paymentDay"));
        assertThat(after.get("endDate")).isEqualTo(before.get("endDate"));
        assertThat(after.get("remainingPayments")).isEqualTo(before.get("remainingPayments"));
        assertThat(after.get("createdAt")).isEqualTo(before.get("createdAt"));
        assertThat(((BigDecimal) after.get("amount")).compareTo(new BigDecimal("1234.56"))).isZero();

        LocalDateTime repoCreatedAt = obligationRepository
                .findByIdAndUser(UUID.fromString(id), user).orElseThrow().getCreatedAt();
        assertThat(repoCreatedAt).isNotNull();
    }

    @Test
    void findById_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> obligationRepository.findById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseBodyAsList(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), List.class);
    }

    private String registerAndLogin(String email, String password) throws Exception {
        mvc.perform(post(AUTH_REGISTER)
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest(email, password))))
                .andExpect(status().isCreated());

        MvcResult result = mvc.perform(post(AUTH_LOGIN)
                        .contentType(APPLICATION_JSON)
                        .content(json(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        return (String) parseBody(result).get("accessToken");
    }

    private Map<String, Object> fixedTermBody() {
        return Map.of(
                "name", "Loan",
                "amount", 500.00,
                "category", "IMPORTANT",
                "period", "FIXED_TERM",
                "paymentDay", 10,
                "endDate", LocalDate.now().plusMonths(6).toString(),
                "remainingPayments", 6
        );
    }
}
