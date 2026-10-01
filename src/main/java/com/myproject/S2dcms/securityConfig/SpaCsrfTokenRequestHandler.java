package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;

import java.util.function.Supplier;

/**
 * SPA-compatible CSRF handler.
 *
 * Spring Security 6 defaults to XOR-masked tokens (BREACH mitigation: the published value
 * differs on every response). Masking is meant for a client that reads the token straight out
 * of the cookie. This SPA cannot - it is served from a different origin than the API, so
 * document.cookie is empty for it - and therefore obtains the token from the CORS-exposed
 * X-XSRF-TOKEN header or from GET /api/auth/csrf.
 *
 * That distinction decides the design. A masked value in the header/JSON body while the cookie
 * holds the raw token gives the SPA and the server two different representations of one secret,
 * and they drift out of agreement: the client replays the masked value, the server decodes it
 * against the raw cookie, and the write is rejected. Publishing and resolving the same RAW
 * value removes that whole class of failure, and costs nothing meaningful here because the
 * token is deliberately handed to JavaScript anyway.
 *
 * The trade-off is losing BREACH masking. That is a deliberate, recorded choice: it is only
 * exploitable when a secret travels inside a compressed response mixed with attacker-controlled
 * input, and this token is served in its own JSON body to a client that is expected to read it.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken
    ) {
        // Publish the RAW token into the request attribute. CsrfCookieFilter copies it into the
        // CORS-exposed X-XSRF-TOKEN header, and GET /api/auth/csrf serves the same value in its
        // body, so the SPA echoes back exactly what it received and the cookie holds the same
        // string. One representation, end to end.
        this.plain.handle(request, response, csrfToken);
        // Force deferred token load so CookieCsrfTokenRepository can write the cookie
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return this.plain.resolveCsrfTokenValue(request, csrfToken);
    }
}
