package com.example.finance_hq.auth;

import com.example.finance_hq.TestcontainersConfiguration;
import com.example.finance_hq.auth.dto.LoginRequest;
import com.example.finance_hq.auth.dto.LogoutRequest;
import com.example.finance_hq.auth.dto.RefreshRequest;
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
import org.springframework.web.context.WebApplicationContext;

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
class AuthControllerIntegrationTest {

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

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private Map<String, Object> loginAndGetTokens(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return parseBody(result);
    }

    @Test
    void register_201_validCredentials() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("new_user@example.com", "Test1234!"))))
                .andExpect(status().isCreated());
    }

    @Test
    void register_409_duplicateEmail() throws Exception {
        String body = json(new RegisterRequest("dup@example.com", "Test1234!"));
        mvc.perform(post("/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void register_400_weakPassword() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("weak@example.com", "weakpass"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_400_invalidEmail() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("not-an-email", "Test1234!"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_200_correctCredentials() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("login_ok@example.com", "Test1234!"))))
                .andExpect(status().isCreated());

        Map<String, Object> tokens = loginAndGetTokens("login_ok@example.com", "Test1234!");

        assertThat(tokens.get("accessToken")).isNotNull().asString().isNotBlank();
        assertThat(tokens.get("refreshToken")).isNotNull().asString().isNotBlank();
    }

    @Test
    void login_401_wrongPassword() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("wrongpw@example.com", "Test1234!"))))
                .andExpect(status().isCreated());

        mvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(new LoginRequest("wrongpw@example.com", "WrongPass1!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_401_unknownEmail() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(new LoginRequest("nobody@example.com", "Test1234!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_200_validRefreshToken() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("refresh_valid@example.com", "Test1234!"))))
                .andExpect(status().isCreated());

        Map<String, Object> loginTokens = loginAndGetTokens("refresh_valid@example.com", "Test1234!");
        String refreshToken = (String) loginTokens.get("refreshToken");

        mvc.perform(post("/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_401_invalidRefreshToken() throws Exception {
        mvc.perform(post("/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RefreshRequest("not-a-real-token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_204_validRefreshToken() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("logout_user@example.com", "Test1234!"))))
                .andExpect(status().isCreated());

        Map<String, Object> loginTokens = loginAndGetTokens("logout_user@example.com", "Test1234!");
        String refreshToken = (String) loginTokens.get("refreshToken");

        mvc.perform(post("/auth/logout")
                        .contentType(APPLICATION_JSON)
                        .content(json(new LogoutRequest(refreshToken))))
                .andExpect(status().isNoContent());
    }

    @Test
    void protectedEndpoint_401_noToken() throws Exception {
        mvc.perform(get("/some-protected-path"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_401_tamperedBearerToken() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("tamper_test@example.com", "Test1234!"))))
                .andExpect(status().isCreated());

        Map<String, Object> loginTokens = loginAndGetTokens("tamper_test@example.com", "Test1234!");
        String accessToken = (String) loginTokens.get("accessToken");
        String tampered = accessToken.substring(0, accessToken.length() - 4) + "XXXX";

        mvc.perform(get("/some-protected-path")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_notUnauthorized_validBearerToken() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(new RegisterRequest("bearer_test@example.com", "Test1234!"))))
                .andExpect(status().isCreated());

        Map<String, Object> loginTokens = loginAndGetTokens("bearer_test@example.com", "Test1234!");
        String accessToken = (String) loginTokens.get("accessToken");

        MvcResult result = mvc.perform(get("/some-unknown-path")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
    }
}
