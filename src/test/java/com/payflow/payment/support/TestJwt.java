package com.payflow.payment.support;

import com.payflow.payment.security.Caller;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** MockMvc authentication shortcuts; these bypass token decoding. */
public final class TestJwt {

    /** Test-only HS256 key; never used outside the test classpath. */
    public static final String TEST_SECRET = "test-only-hmac-secret-0123456789abcdef";

    public static final String ALICE = "alice";
    public static final String BOB = "bob";

    private TestJwt() {
    }

    public static RequestPostProcessor customer(String subject) {
        return jwt().jwt(token -> token.subject(subject));
    }

    public static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("ops-admin"))
                .authorities(new SimpleGrantedAuthority(Caller.ADMIN_AUTHORITY));
    }
}
