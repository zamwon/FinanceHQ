package com.example.finance_hq.dashboard;

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
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class DashboardControllerIntegrationTest {

    public static final String AUTH_REGISTER = "/auth/register";
    public static final String AUTH_LOGIN = "/auth/login";
    public static final String API_TRANSACTIONS = "/api/transactions";
    public static final String API_DASHBOARD_SUMMARY = "/api/dashboard/summary";
    public static final String API_DASHBOARD_TRENDS = "/api/dashboard/trends";

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

    @Test
    void summary_400_noToken() throws Exception {
        mvc.perform(get(API_DASHBOARD_SUMMARY))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void summary_200_emptyMonth_showsZeros() throws Exception {
        String token = registerAndLogin("dash_empty@test.com", "Test1234!");

        MvcResult result = mvc.perform(get(API_DASHBOARD_SUMMARY)
                                               .param("month", YearMonth.now().toString())
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("month")).isEqualTo(YearMonth.now().toString());
        assertThat(((Number) body.get("totalIncome")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) body.get("totalExpenses")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) body.get("netBalance")).doubleValue()).isEqualTo(0.0);
        assertThat((List<?>) body.get("expensesByCategory")).isEmpty();
        assertThat((List<?>) body.get("incomeByCategory")).isEmpty();
    }

    @Test
    void summary_200_correctTotalsAndNetBalance() throws Exception {
        String token = registerAndLogin("dash_totals@test.com", "Test1234!");
        String today = LocalDate.now().toString();

        postTransaction(token, expenseBody("FOOD", 100.0, today));
        postTransaction(token, expenseBody("HOUSING", 500.0, today));
        postTransaction(token, incomeBody("SALARY", 3000.0, today));

        MvcResult result = mvc.perform(get(API_DASHBOARD_SUMMARY)
                                               .param("month", YearMonth.now().toString())
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(((Number) body.get("totalExpenses")).doubleValue()).isEqualTo(600.0);
        assertThat(((Number) body.get("totalIncome")).doubleValue()).isEqualTo(3000.0);
        assertThat(((Number) body.get("netBalance")).doubleValue()).isEqualTo(2400.0);
    }

    @Test
    void summary_200_recurringTransactionExcludedFromTotals() throws Exception {
        String token = registerAndLogin("dash_recurring@test.com", "Test1234!");
        String today = LocalDate.now().toString();

        postTransaction(token, incomeBody("SALARY", 1000.0, today));

        Map<String, Object> recurringBody = new HashMap<>();
        recurringBody.put("type", "INCOME");
        recurringBody.put("category", "SALARY");
        recurringBody.put("amount", 5000.0);
        recurringBody.put("period", "RECURRING");
        recurringBody.put("paymentDay", 25);
        postTransaction(token, recurringBody);

        MvcResult result = mvc.perform(get(API_DASHBOARD_SUMMARY)
                                               .param("month", YearMonth.now().toString())
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(((Number) body.get("totalIncome")).doubleValue()).isEqualTo(1000.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summary_200_categoryBreakdownGroupsCorrectly() throws Exception {
        String token = registerAndLogin("dash_breakdown@test.com", "Test1234!");
        String today = LocalDate.now().toString();

        postTransaction(token, expenseBody("FOOD", 50.0, today));
        postTransaction(token, expenseBody("FOOD", 75.0, today));
        postTransaction(token, expenseBody("HOUSING", 200.0, today));

        MvcResult result = mvc.perform(get(API_DASHBOARD_SUMMARY)
                                               .param("month", YearMonth.now().toString())
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        List<Map<String, Object>> expensesByCategory = (List<Map<String, Object>>) body.get("expensesByCategory");

        assertThat(expensesByCategory).hasSize(2);

        Map<String, Object> foodRow = expensesByCategory.stream()
                .filter(r -> "FOOD".equals(r.get("category")))
                .findFirst()
                .orElseThrow();
        assertThat(((Number) foodRow.get("total")).doubleValue()).isEqualTo(125.0);
        assertThat(((Number) foodRow.get("count")).intValue()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void trends_200_returnsNMonthsAscending() throws Exception {
        String token = registerAndLogin("dash_trends@test.com", "Test1234!");

        MvcResult result = mvc.perform(get(API_DASHBOARD_TRENDS)
                                               .param("months", "3")
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        List<Map<String, Object>> items = parseBodyAsList(result);
        assertThat(items).hasSize(3);

        // Verify ascending month order
        String prev = null;
        for (Map<String, Object> item : items) {
            String month = (String) item.get("month");
            if (prev != null) {
                assertThat(month).isGreaterThan(prev);
            }
            prev = month;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void trends_200_currentMonthMatchesSummary() throws Exception {
        String token = registerAndLogin("dash_trends_match@test.com", "Test1234!");
        String today = LocalDate.now().toString();

        postTransaction(token, expenseBody("FOOD", 200.0, today));
        postTransaction(token, incomeBody("SALARY", 1500.0, today));

        MvcResult summaryResult = mvc.perform(get(API_DASHBOARD_SUMMARY)
                                                      .param("month", YearMonth.now().toString())
                                                      .header("Authorization", "Bearer " + token))
                                    .andExpect(status().isOk())
                                    .andReturn();

        MvcResult trendsResult = mvc.perform(get(API_DASHBOARD_TRENDS)
                                                     .param("months", "1")
                                                     .header("Authorization", "Bearer " + token))
                                   .andExpect(status().isOk())
                                   .andReturn();

        Map<String, Object> summary = parseBody(summaryResult);
        List<Map<String, Object>> trends = parseBodyAsList(trendsResult);

        assertThat(trends).hasSize(1);
        Map<String, Object> currentTrend = trends.get(0);
        assertThat(((Number) currentTrend.get("totalExpenses")).doubleValue())
                .isEqualTo(((Number) summary.get("totalExpenses")).doubleValue());
        assertThat(((Number) currentTrend.get("totalIncome")).doubleValue())
                .isEqualTo(((Number) summary.get("totalIncome")).doubleValue());
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

    private void postTransaction(String token, Map<String, Object> body) throws Exception {
        mvc.perform(post(API_TRANSACTIONS)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(body)))
           .andExpect(status().isCreated());
    }

    private Map<String, Object> expenseBody(String category, double amount, String date) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPENSE");
        body.put("category", category);
        body.put("amount", amount);
        body.put("date", date);
        return body;
    }

    private Map<String, Object> incomeBody(String category, double amount, String date) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "INCOME");
        body.put("category", category);
        body.put("amount", amount);
        body.put("date", date);
        return body;
    }
}
