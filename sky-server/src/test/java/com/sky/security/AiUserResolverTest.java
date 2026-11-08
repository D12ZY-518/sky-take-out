package com.sky.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sky.constant.JwtClaimsConstant;
import com.sky.exception.UserNotLoginException;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;

class AiUserResolverTest {

    private static final String TOKEN_HEADER = "authentication";
    private static final String SECRET = "resolver-test-secret";

    private JwtProperties jwtProperties;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setUserTokenName(TOKEN_HEADER);
        jwtProperties.setUserSecretKey(SECRET);
        request = mock(HttpServletRequest.class);
    }

    @Test
    void shouldUseDemoUserWhenTokenIsMissingAndDemoIsEnabled() {
        AiUserResolver resolver = new AiUserResolver(jwtProperties, true);

        assertEquals(1L, resolver.resolve(request));
    }

    @Test
    void shouldRejectMissingTokenWhenDemoIsDisabled() {
        AiUserResolver resolver = new AiUserResolver(jwtProperties, false);

        assertThrows(UserNotLoginException.class, () -> resolver.resolve(request));
    }

    @Test
    void shouldResolveUserIdFromValidToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, 42L);
        String token = JwtUtil.createJWT(SECRET, 60_000L, claims);
        when(request.getHeader(TOKEN_HEADER)).thenReturn(token);
        AiUserResolver resolver = new AiUserResolver(jwtProperties, false);

        assertEquals(42L, resolver.resolve(request));
    }

    @Test
    void shouldRejectInvalidTokenEvenWhenDemoIsEnabled() {
        when(request.getHeader(TOKEN_HEADER)).thenReturn("invalid-token");
        AiUserResolver resolver = new AiUserResolver(jwtProperties, true);

        assertThrows(UserNotLoginException.class, () -> resolver.resolve(request));
    }
}
