package com.example.finance_hq.obligation;

import com.example.finance_hq.TestcontainersConfiguration;
import com.example.finance_hq.auth.dto.LoginRequest;
import com.example.finance_hq.auth.dto.RegisterRequest;
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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class ObligationControllerIntegrationTest {

    public static final String AUTH_REGISTER = "/auth/register";
    public static final String AUTH_LOGIN = "/auth/login";
    public static final String API_OBLIGATIONS = "/api/obligations";

    @Autowired
    WebApplicationContext wac;

    private final ObjectMapper objectMapper = new ObjectMapper();

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac)
                             .apply(springSecurity())
                             .build();
    }

    // ── Auth boundary ──────────────────────────────────────────────────────────

    @Test
    void list_401_noToken() throws Exception {
        mvc.perform(get(API_OBLIGATIONS))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void create_401_noToken() throws Exception {
        mvc.perform(post(API_OBLIGATIONS)
                            .contentType(APPLICATION_JSON)
                            .content(json(recurringBody())))
           .andExpect(status().isUnauthorized());
    }

    // ── CRUD happy paths ───────────────────────────────────────────────────────

    @Test
    void list_200_emptyArray_whenNoObligations() throws Exception {
        String token = registerAndLogin("list_empty@test.com", "Test1234!");

        MvcResult result = mvc.perform(get(API_OBLIGATIONS)
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        assertThat(parseBodyAsList(result)).isEmpty();
    }

    @Test
    void create_201_recurring_hasIdAndNextDueDate() throws Exception {
        String token = registerAndLogin("create_recurring@test.com", "Test1234!");

        MvcResult result = mvc.perform(post(API_OBLIGATIONS)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(recurringBody())))
                              .andExpect(status().isCreated())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("id")).isNotNull().asString().isNotBlank();
        assertThat(body.get("nextDueDate")).isNotNull();
        assertThat(body.get("category")).isEqualTo("ESSENTIAL");
    }

    @Test
    void create_201_fixedTerm_hasEndDateAndRemainingPayments() throws Exception {
        String token = registerAndLogin("create_fixed@test.com", "Test1234!");

        MvcResult result = mvc.perform(post(API_OBLIGATIONS)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(fixedTermBody())))
                              .andExpect(status().isCreated())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("endDate")).isNotNull();
        assertThat(body.get("remainingPayments")).isEqualTo(6);
    }

    @Test
    void list_200_containsCreatedObligation() throws Exception {
        String token = registerAndLogin("list_after_create@test.com", "Test1234!");

        mvc.perform(post(API_OBLIGATIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(recurringBody())))
           .andExpect(status().isCreated());

        MvcResult result = mvc.perform(get(API_OBLIGATIONS)
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        List<Map<String, Object>> body = parseBodyAsList(result);
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("name")).isEqualTo("Rent");
    }

    @Test
    void update_200_amountChanged() throws Exception {
        String token = registerAndLogin("update_amount@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_OBLIGATIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(recurringBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        MvcResult result = mvc.perform(patch(API_OBLIGATIONS + "/" + id)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(Map.of("amount", 999.99))))
                              .andExpect(status().isOk())
                              .andReturn();

        assertThat(((Number) parseBody(result).get("amount")).doubleValue()).isEqualTo(999.99);
    }

    @Test
    void delete_204_obligationGone() throws Exception {
        String token = registerAndLogin("delete_obligation@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_OBLIGATIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(recurringBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(delete(API_OBLIGATIONS + "/" + id)
                            .header("Authorization", "Bearer " + token))
           .andExpect(status().isNoContent());

        MvcResult listResult = mvc.perform(get(API_OBLIGATIONS)
                                                   .header("Authorization", "Bearer " + token))
                                  .andExpect(status().isOk())
                                  .andReturn();

        assertThat(parseBodyAsList(listResult)).isEmpty();
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    @Test
    void create_400_fixedTerm_missingEndDate() throws Exception {
        String token = registerAndLogin("ft_no_enddate@test.com", "Test1234!");

        Map<String, Object> body = new HashMap<>(fixedTermBody());
        body.remove("endDate");

        mvc.perform(post(API_OBLIGATIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(body)))
           .andExpect(status().isBadRequest());
    }

    @Test
    void create_400_fixedTerm_missingRemainingPayments() throws Exception {
        String token = registerAndLogin("ft_no_remaining@test.com", "Test1234!");

        Map<String, Object> body = new HashMap<>(fixedTermBody());
        body.remove("remainingPayments");

        mvc.perform(post(API_OBLIGATIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(body)))
           .andExpect(status().isBadRequest());
    }

    @Test
    void create_400_amountZero() throws Exception {
        String token = registerAndLogin("amount_zero@test.com", "Test1234!");

        Map<String, Object> body = new HashMap<>(recurringBody());
        body.put("amount", 0);

        mvc.perform(post(API_OBLIGATIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(body)))
           .andExpect(status().isBadRequest());
    }

    @Test
    void create_400_paymentDayTooHigh() throws Exception {
        String token = registerAndLogin("payday_too_high@test.com", "Test1234!");

        Map<String, Object> body = new HashMap<>(recurringBody());
        body.put("paymentDay", 32);

        mvc.perform(post(API_OBLIGATIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(body)))
           .andExpect(status().isBadRequest());
    }

    // ── Ownership ──────────────────────────────────────────────────────────────

    @Test
    void update_404_wrongUser() throws Exception {
        String tokenA = registerAndLogin("owner_patch_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("owner_patch_b@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_OBLIGATIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + tokenA)
                                                .content(json(recurringBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(patch(API_OBLIGATIONS + "/" + id)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenB)
                            .content(json(Map.of("amount", 1.00))))
           .andExpect(status().isNotFound());
    }

    @Test
    void delete_404_wrongUser() throws Exception {
        String tokenA = registerAndLogin("owner_delete_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("owner_delete_b@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_OBLIGATIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + tokenA)
                                                .content(json(recurringBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(delete(API_OBLIGATIONS + "/" + id)
                            .header("Authorization", "Bearer " + tokenB))
           .andExpect(status().isNotFound());
    }

    @Test
    void list_200_doesNotReturnOtherUsersObligations() throws Exception {
        String tokenA = registerAndLogin("read_isolation_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("read_isolation_b@test.com", "Test1234!");

        mvc.perform(post(API_OBLIGATIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenA)
                            .content(json(recurringBody())))
           .andExpect(status().isCreated());

        MvcResult result = mvc.perform(get(API_OBLIGATIONS)
                                               .header("Authorization", "Bearer " + tokenB))
                              .andExpect(status().isOk())
                              .andReturn();

        assertThat(parseBodyAsList(result)).isEmpty();
    }

    // ── Additional update/delete validation ───────────────────────────────────

    @Test
    void update_400_bothFieldsNull() throws Exception {
        String token = registerAndLogin("update_both_null@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_OBLIGATIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(recurringBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(patch(API_OBLIGATIONS + "/" + id)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content("{}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void update_400_amountZero() throws Exception {
        String token = registerAndLogin("update_amount_zero@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_OBLIGATIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(recurringBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(patch(API_OBLIGATIONS + "/" + id)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(Map.of("amount", 0))))
           .andExpect(status().isBadRequest());
    }

    @Test
    void update_404_notFound() throws Exception {
        String token = registerAndLogin("update_not_found@test.com", "Test1234!");
        mvc.perform(patch(API_OBLIGATIONS + "/" + UUID.randomUUID())
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(Map.of("amount", 1.00))))
           .andExpect(status().isNotFound());
    }

    @Test
    void delete_404_notFound() throws Exception {
        String token = registerAndLogin("delete_not_found@test.com", "Test1234!");

        mvc.perform(delete(API_OBLIGATIONS + "/" + UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
           .andExpect(status().isNotFound());
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

    private Map<String, Object> recurringBody() {
        return Map.of(
                "name", "Rent",
                "amount", 1500.00,
                "category", "ESSENTIAL",
                "period", "RECURRING",
                "paymentDay", 15
        );
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
