package com.akshatha.urlshortener;

import com.akshatha.urlshortener.service.ClickCounter;
import com.akshatha.urlshortener.service.IdAllocator;
import com.akshatha.urlshortener.service.NoOpClickCounter;
import com.akshatha.urlshortener.service.NoOpUrlCache;
import com.akshatha.urlshortener.service.SequenceIdAllocator;
import com.akshatha.urlshortener.service.UrlCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The M4 control group, booted for real: no Redis container at all.
 *
 * This exists because the baseline is a claim about a configuration, and a
 * configuration that is never started is a configuration that has quietly
 * rotted. If this test fails, the before-number in the README cannot be
 * reproduced, which makes the after-number meaningless.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class BaselineProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("shortener.rate-limit.enabled", () -> false);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** The default profile must be the uncached one, or the baseline is a lie. */
    @Test
    void theDefaultProfileWiresTheControlGroup() {
        assertThat(applicationContext.getBean(UrlCache.class)).isInstanceOf(NoOpUrlCache.class);
        assertThat(applicationContext.getBean(IdAllocator.class))
                .isInstanceOf(SequenceIdAllocator.class);
        assertThat(applicationContext.getBean(ClickCounter.class))
                .isInstanceOf(NoOpClickCounter.class);
    }

    @Test
    void shortensAndRedirectsWithoutAnyRedis() throws Exception {
        String code = mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"longUrl": "https://example.com/baseline"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"shortCode\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/baseline"));
    }

    @Test
    void unknownCodeStill404sWithoutACache() throws Exception {
        mockMvc.perform(get("/zzzzzzz")).andExpect(status().isNotFound());
    }
}
