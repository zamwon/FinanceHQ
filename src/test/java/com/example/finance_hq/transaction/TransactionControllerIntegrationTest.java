package com.example.finance_hq.transaction;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class TransactionControllerIntegrationTest {

    public static final String AUTH_REGISTER = "/auth/register";
    public static final String AUTH_LOGIN = "/auth/login";
    public static final String API_TRANSACTIONS = "/api/transactions";

    @Autowired
    WebApplicationContext wac;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
        mvc.perform(get(API_TRANSACTIONS))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void create_401_noToken() throws Exception {
        mvc.perform(post(API_TRANSACTIONS)
                            .contentType(APPLICATION_JSON)
                            .content(json(oneOffExpenseBody())))
           .andExpect(status().isUnauthorized());
    }

    // ── CRUD happy paths ───────────────────────────────────────────────────────

    @Test
    void list_200_emptyList_forNewUser() throws Exception {
        String token = registerAndLogin("txn_list_empty@test.com", "Test1234!");

        MvcResult result = mvc.perform(get(API_TRANSACTIONS)
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        assertThat(parseBodyAsList(result)).isEmpty();
    }

    @Test
    void create_201_oneOff_expense() throws Exception {
        String token = registerAndLogin("txn_create_expense@test.com", "Test1234!");

        MvcResult result = mvc.perform(post(API_TRANSACTIONS)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(oneOffExpenseBody())))
                              .andExpect(status().isCreated())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("id")).isNotNull().asString().isNotBlank();
        assertThat(body.get("type")).isEqualTo("EXPENSE");
        assertThat(body.get("category")).isEqualTo("HOUSING");
        assertThat(((Number) body.get("amount")).doubleValue()).isEqualTo(500.00);
        assertThat(body.get("nextExpectedDate")).isNull();
    }

    @Test
    void create_201_recurring_income_hasNextExpectedDate() throws Exception {
        String token = registerAndLogin("txn_create_income@test.com", "Test1234!");

        MvcResult result = mvc.perform(post(API_TRANSACTIONS)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(recurringIncomeBody())))
                              .andExpect(status().isCreated())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("type")).isEqualTo("INCOME");
        assertThat(body.get("category")).isEqualTo("SALARY");
        assertThat(body.get("nextExpectedDate")).isNotNull();
        assertThat(body.get("date")).isNull();
    }

    @Test
    void update_200_fieldsChanged() throws Exception {
        String token = registerAndLogin("txn_update@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_TRANSACTIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(oneOffExpenseBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        MvcResult result = mvc.perform(patch(API_TRANSACTIONS + "/" + id)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(Map.of("amount", 750.00, "description", "Updated"))))
                              .andExpect(status().isOk())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(((Number) body.get("amount")).doubleValue()).isEqualTo(750.00);
        assertThat(body.get("description")).isEqualTo("Updated");
    }

    @Test
    void delete_204_transactionGone() throws Exception {
        String token = registerAndLogin("txn_delete@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_TRANSACTIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(oneOffExpenseBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(delete(API_TRANSACTIONS + "/" + id)
                            .header("Authorization", "Bearer " + token))
           .andExpect(status().isNoContent());

        MvcResult listResult = mvc.perform(get(API_TRANSACTIONS)
                                                   .header("Authorization", "Bearer " + token))
                                  .andExpect(status().isOk())
                                  .andReturn();

        assertThat(parseBodyAsList(listResult)).isEmpty();
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    @Test
    void create_400_unknownCategoryForType() throws Exception {
        String token = registerAndLogin("txn_bad_category@test.com", "Test1234!");

        Map<String, Object> body = new HashMap<>(oneOffExpenseBody());
        body.put("category", "SALARY"); // SALARY is an income category, not expense

        mvc.perform(post(API_TRANSACTIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(body)))
           .andExpect(status().isBadRequest());
    }

    @Test
    void create_400_fixedTerm_missingEndDate() throws Exception {
        String token = registerAndLogin("txn_ft_no_enddate@test.com", "Test1234!");

        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPENSE");
        body.put("category", "FOOD");
        body.put("amount", 100.00);
        body.put("period", "FIXED_TERM");
        body.put("paymentDay", 10);
        body.put("remainingPayments", 6);
        // endDate intentionally omitted

        mvc.perform(post(API_TRANSACTIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(body)))
           .andExpect(status().isBadRequest());
    }

    @Test
    void update_400_allNullBody() throws Exception {
        String token = registerAndLogin("txn_update_null@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_TRANSACTIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(oneOffExpenseBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(patch(API_TRANSACTIONS + "/" + id)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content("{}"))
           .andExpect(status().isBadRequest());
    }

    // ── Ownership ──────────────────────────────────────────────────────────────

    @Test
    void update_404_wrongUser() throws Exception {
        String tokenA = registerAndLogin("txn_owner_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("txn_owner_b@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_TRANSACTIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + tokenA)
                                                .content(json(oneOffExpenseBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(patch(API_TRANSACTIONS + "/" + id)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenB)
                            .content(json(Map.of("amount", 1.00))))
           .andExpect(status().isNotFound());
    }

    @Test
    void delete_404_wrongUser() throws Exception {
        String tokenA = registerAndLogin("txn_del_owner_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("txn_del_owner_b@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(API_TRANSACTIONS)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + tokenA)
                                                .content(json(oneOffExpenseBody())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(delete(API_TRANSACTIONS + "/" + id)
                            .header("Authorization", "Bearer " + tokenB))
           .andExpect(status().isNotFound());
    }

    @Test
    void list_200_doesNotReturnOtherUsersTransactions() throws Exception {
        String tokenA = registerAndLogin("txn_iso_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("txn_iso_b@test.com", "Test1234!");

        mvc.perform(post(API_TRANSACTIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenA)
                            .content(json(oneOffExpenseBody())))
           .andExpect(status().isCreated());

        MvcResult result = mvc.perform(get(API_TRANSACTIONS)
                                               .header("Authorization", "Bearer " + tokenB))
                              .andExpect(status().isOk())
                              .andReturn();

        assertThat(parseBodyAsList(result)).isEmpty();
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

    private Map<String, Object> oneOffExpenseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPENSE");
        body.put("category", "HOUSING");
        body.put("amount", 500.00);
        body.put("description", "Monthly rent");
        body.put("date", LocalDate.now().toString());
        return body;
    }

    private Map<String, Object> recurringIncomeBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "INCOME");
        body.put("category", "SALARY");
        body.put("amount", 3000.00);
        body.put("period", "RECURRING");
        body.put("paymentDay", 25);
        return body;
    }
}
