package com.example.finance_hq.portfolio;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.finance_hq.portfolio.PortfolioAssetController.BASE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class PortfolioAssetControllerIntegrationTest {

    private static final String AUTH_REGISTER = "/auth/register";
    private static final String AUTH_LOGIN = "/auth/login";

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
        mvc.perform(get(BASE_PATH))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void create_401_noToken() throws Exception {
        mvc.perform(post(BASE_PATH)
                            .contentType(APPLICATION_JSON)
                            .content(json(btcRequest())))
           .andExpect(status().isUnauthorized());
    }

    // ── CRUD happy paths ───────────────────────────────────────────────────────

    @Test
    void list_200_emptyList_forNewUser() throws Exception {
        String token = registerAndLogin("portfolio_list_empty@test.com", "Test1234!");

        MvcResult result = mvc.perform(get(BASE_PATH)
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        assertThat(parseBodyAsList(result)).isEmpty();
    }

    @Test
    void create_201_returnsAsset() throws Exception {
        String token = registerAndLogin("portfolio_create@test.com", "Test1234!");

        MvcResult result = mvc.perform(post(BASE_PATH)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(btcRequest())))
                              .andExpect(status().isCreated())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("id")).isNotNull().asString().isNotBlank();
        assertThat(body.get("ticker")).isEqualTo("BTC");
        assertThat(body.get("assetGroup")).isEqualTo("Crypto");
        assertThat(body.get("currentPricePln")).isNull();
        assertThat(body.get("currentValuePln")).isNull();
    }

    @Test
    void create_201_thenList_200_containsAsset() throws Exception {
        String token = registerAndLogin("portfolio_list_after_create@test.com", "Test1234!");

        mvc.perform(post(BASE_PATH)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(btcRequest())))
           .andExpect(status().isCreated());

        MvcResult result = mvc.perform(get(BASE_PATH)
                                               .header("Authorization", "Bearer " + token))
                              .andExpect(status().isOk())
                              .andReturn();

        List<Map<String, Object>> list = parseBodyAsList(result);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("ticker")).isEqualTo("BTC");
    }

    @Test
    void create_409_duplicateTicker() throws Exception {
        String token = registerAndLogin("portfolio_dup@test.com", "Test1234!");

        mvc.perform(post(BASE_PATH)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(json(btcRequest())))
           .andExpect(status().isCreated());

        MvcResult result = mvc.perform(post(BASE_PATH)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(btcRequest())))
                              .andExpect(status().isConflict())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(body.get("detail").toString()).contains("BTC");
    }

    @Test
    void update_200_fieldsChanged() throws Exception {
        String token = registerAndLogin("portfolio_update@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(BASE_PATH)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(btcRequest())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        Map<String, Object> patch = new HashMap<>();
        patch.put("shares", 2.5);
        patch.put("assetGroup", "Digital Assets");

        MvcResult result = mvc.perform(patch(BASE_PATH + "/" + id)
                                               .contentType(APPLICATION_JSON)
                                               .header("Authorization", "Bearer " + token)
                                               .content(json(patch)))
                              .andExpect(status().isOk())
                              .andReturn();

        Map<String, Object> body = parseBody(result);
        assertThat(((Number) body.get("shares")).doubleValue()).isEqualTo(2.5);
        assertThat(body.get("assetGroup")).isEqualTo("Digital Assets");
    }

    @Test
    void delete_204_assetGone() throws Exception {
        String token = registerAndLogin("portfolio_delete@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(BASE_PATH)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + token)
                                                .content(json(btcRequest())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(delete(BASE_PATH + "/" + id)
                            .header("Authorization", "Bearer " + token))
           .andExpect(status().isNoContent());

        MvcResult listResult = mvc.perform(get(BASE_PATH)
                                                   .header("Authorization", "Bearer " + token))
                                  .andExpect(status().isOk())
                                  .andReturn();

        assertThat(parseBodyAsList(listResult)).isEmpty();
    }

    // ── Ownership / cross-user isolation ───────────────────────────────────────

    @Test
    void list_200_doesNotReturnOtherUsersAssets() throws Exception {
        String tokenA = registerAndLogin("portfolio_iso_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("portfolio_iso_b@test.com", "Test1234!");

        mvc.perform(post(BASE_PATH)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenA)
                            .content(json(btcRequest())))
           .andExpect(status().isCreated());

        MvcResult result = mvc.perform(get(BASE_PATH)
                                               .header("Authorization", "Bearer " + tokenB))
                              .andExpect(status().isOk())
                              .andReturn();

        assertThat(parseBodyAsList(result)).isEmpty();
    }

    @Test
    void update_404_wrongUser() throws Exception {
        String tokenA = registerAndLogin("portfolio_owner_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("portfolio_owner_b@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(BASE_PATH)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + tokenA)
                                                .content(json(btcRequest())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(patch(BASE_PATH + "/" + id)
                            .contentType(APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenB)
                            .content(json(Map.of("shares", 1.0))))
           .andExpect(status().isNotFound());
    }

    @Test
    void delete_404_wrongUser() throws Exception {
        String tokenA = registerAndLogin("portfolio_del_a@test.com", "Test1234!");
        String tokenB = registerAndLogin("portfolio_del_b@test.com", "Test1234!");

        MvcResult created = mvc.perform(post(BASE_PATH)
                                                .contentType(APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + tokenA)
                                                .content(json(btcRequest())))
                               .andExpect(status().isCreated())
                               .andReturn();

        String id = (String) parseBody(created).get("id");

        mvc.perform(delete(BASE_PATH + "/" + id)
                            .header("Authorization", "Bearer " + tokenB))
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

    private Map<String, Object> btcRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("ticker", "BTC");
        body.put("assetGroup", "Crypto");
        body.put("shares", 0.5);
        body.put("avgBuyPricePln", 150000.00);
        body.put("avgBuyPriceAssetCurrency", 37500.00);
        body.put("purchaseValuePln", 75000.00);
        body.put("purchaseValueAssetCurrency", 18750.00);
        return body;
    }
}
