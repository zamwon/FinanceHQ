package com.example.finance_hq.web;

import com.example.finance_hq.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SpaForwardingConfigTest {

    @Autowired
    WebApplicationContext wac;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    void dashboardForwardsToIndexHtml() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void authLoginNotForwarded() throws Exception {
        // POST-only endpoint returns 405 for GET — controller wins, not the SPA forwarder
        mvc.perform(get("/auth/login"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void staticAssetNotForwarded() throws Exception {
        // Paths with a dot bypass the forwarder; absent asset → 404, not the SPA shell
        mvc.perform(get("/main-AB12.js"))
                .andExpect(status().isNotFound());
    }
}
