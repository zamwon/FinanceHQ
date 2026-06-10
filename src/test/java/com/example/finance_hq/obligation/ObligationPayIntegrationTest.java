package com.example.finance_hq.obligation;

import com.example.finance_hq.TestcontainersConfiguration;
import com.example.finance_hq.auth.dto.LoginRequest;
import com.example.finance_hq.auth.dto.RegisterRequest;
import com.example.finance_hq.notification.EmailSender;
import com.example.finance_hq.notification.NotificationService;
import com.example.finance_hq.user.User;
import com.example.finance_hq.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class ObligationPayIntegrationTest {

    static final String AUTH_REGISTER = "/auth/register";
    static final String AUTH_LOGIN = "/auth/login";
    static final String API_OBLIGATIONS = "/api/obligations";
    static final String API_TRANSACTIONS = "/api/transactions";

    // Monday 2026-06-02; previousBusinessDay(June 3) = June 2
    static final LocalDate SCHEDULER_TODAY = LocalDate.of(2026, 6, 2);

    @Autowired WebApplicationContext wac;
    @Autowired ObligationRepository obligationRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationService notificationService;
    @MockitoBean EmailSender emailSender;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac)
                             .apply(springSecurity())
                             .build();
    }

    // ── Mark Paid happy path ───────────────────────────────────────────────────

    @Test
    void pay_201_createsLinkedTransaction() throws Exception {
        String token = registerAndLogin("pay_create@test.com", "Test1234!");
        String obligationId = createObligation(token);

        MvcResult result = mvc.perform(post(payUrl(obligationId))
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(payBody(LocalDate.now()))))
                              .andExpect(status().isCreated())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("type")).isEqualTo("EXPENSE");
        assertThat(body.get("obligationId")).isEqualTo(obligationId);
        assertThat(body.get("category")).isEqualTo("HOUSING");
    }

    @Test
    void pay_transactionAppearsInTransactionList() throws Exception {
        String token = registerAndLogin("pay_list@test.com", "Test1234!");
        String obligationId = createObligation(token);

        mvc.perform(post(payUrl(obligationId))
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(payBody(LocalDate.now()))))
           .andExpect(status().isCreated());

        MvcResult listResult = mvc.perform(get(API_TRANSACTIONS)
                                                   .header("Authorization", "Bearer " + token))
                                  .andExpect(status().isOk())
                                  .andReturn();

        List<Map<String, Object>> transactions = parseBodyAsList(listResult);
        assertThat(transactions).hasSize(1);
        assertThat(transactions.getFirst().get("obligationId")).isEqualTo(obligationId);
    }

    @Test
    void pay_updatesLastPaidDateOnObligation() throws Exception {
        String token = registerAndLogin("pay_date@test.com", "Test1234!");
        String obligationId = createObligation(token);
        LocalDate paidDate = LocalDate.now();

        mvc.perform(post(payUrl(obligationId))
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(payBody(paidDate))))
           .andExpect(status().isCreated());

        UUID id = UUID.fromString(obligationId);
        Obligation obligation = obligationRepository.findAll().stream()
                .filter(o -> o.getId().equals(id))
                .findFirst()
                .orElseThrow();
        assertThat(obligation.getLastPaidDate()).isEqualTo(paidDate);
    }

    // ── Ownership ──────────────────────────────────────────────────────────────

    @Test
    void pay_404_wrongUser() throws Exception {
        String tokenA = registerAndLogin("pay_owner_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("pay_owner_b@test.com", "Test1234!");
        String obligationId = createObligation(tokenA);

        mvc.perform(post(payUrl(obligationId))
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenB)
                            .content(json(payBody(LocalDate.now()))))
           .andExpect(status().isNotFound());
    }

    // ── Scheduler guard ────────────────────────────────────────────────────────

    @Test
    void schedulerGuard_skipsNotificationWhenAlreadyPaidThisCycle() {
        // paymentDay=3 → nextDueDate=June 3; previousBusinessDay=June 2=SCHEDULER_TODAY → would notify
        // lastPaidDate=May 15 >= nextDueDate.minusMonths(1)=May 3 → guard fires, notification skipped
        User user = userRepository.save(new User("guard_test@example.com", "hash"));
        Obligation obligation = obligationRepository.save(new Obligation(
                user, "Rent", BigDecimal.valueOf(500),
                ObligationCategory.ESSENTIAL, ObligationPeriod.RECURRING, 3, null, null));
        obligation.setLastPaidDate(LocalDate.of(2026, 5, 15));
        obligationRepository.save(obligation);

        notificationService.runDailyNotifications(SCHEDULER_TODAY);

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private String payUrl(String id) {
        return API_OBLIGATIONS + "/" + id + ObligationController.PAY_PATH;
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

    private String createObligation(String token) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Rent");
        body.put("amount", 500.00);
        body.put("category", "ESSENTIAL");
        body.put("period", "RECURRING");
        body.put("paymentDay", 15);

        MvcResult result = mvc.perform(post(API_OBLIGATIONS)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(body)))
                              .andExpect(status().isCreated())
                              .andReturn();

        return (String) parseBody(result).get("id");
    }

    private Map<String, Object> payBody(LocalDate date) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", 500.00);
        body.put("category", "HOUSING");
        body.put("description", "Rent payment");
        body.put("date", date.toString());
        return body;
    }

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
}
