package com.jazzlogs.backend.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jazzlogs.backend.config.SecurityConfig;
import com.jazzlogs.backend.graph.GraphWriteException;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.invalid/.well-known/jwks.json",
    "app.cors.allowed-origins=http://localhost:3000"
})
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithMalformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/me").header("Authorization", "Bearer not-a-valid-jwt"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidJwtReturns200() throws Exception {
        User user = new User(UUID.randomUUID(), "test@example.com");

        when(userService.resolveFromJwt(any(Jwt.class))).thenReturn(user);

        mockMvc.perform(get("/me").with(jwt()))
            .andExpect(status().isOk());
    }

    @Test
    void meWhenGraphWriteFailsReturns502() throws Exception {
        when(userService.resolveFromJwt(any(Jwt.class)))
            .thenThrow(new GraphWriteException("boom", new RuntimeException("connection refused")));

        mockMvc.perform(get("/me").with(jwt()))
            .andExpect(status().isBadGateway());
    }
}
