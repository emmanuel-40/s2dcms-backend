package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

/**
 * SPA-compatible CSRF handler.
 *
 * Spring Security 6 defaults to XOR-masked tokens (BREACH mitigation: the published value
 * differs on every response, so it cannot be recovered from compression side-channels).
 * A browser SPA, however, has to obtain the token cross-origin - it cannot read another
 * site's document.cookie - so this backend also serves it in a CORS-exposed header and in a
 * JSON body.
 *
 * The original pairing (publish masked / resolve raw) is self-inconsistent: the masked value
 * went out to the SPA while resolution only accepted the raw one, so every write was rejected
 * with 403. Publishing raw fixed that but switched BREACH protection off.
 *
 * This handler does both halves properly:
 *   - handle()  publishes the MASKED value, keeping BREACH protection on;
 *   - resolve() accepts EITHER the masked value (what the SPA was actually served) or the raw
 *     cookie value (what a same-origin dev-proxy client sends).
 *
 * Either channel therefore validates, and masking is never weakened.
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken
    ) {
        // Publish the MASKED token into the request attribute. CsrfCookieFilter copies the
        // attribute into the CORS-exposed X-XSRF-TOKEN header, and GET /api/auth/csrf serves
        // that same masked value in its body, so the SPA echoes back exactly what it received.
        this.xor.handle(request, response, csrfToken);
        // Force deferred token load so CookieCsrfTokenRepository can write the cookie
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        // Try the masked form first (the value this handler publishes). XorCsrfTokenRequest-
        // AttributeHandler returns null when the header does not match, so a miss is safe and
        // simply falls through to the raw comparison below.
        String masked = safeResolve(this.xor, request, csrfToken);
        if (masked != null) {
            return masked;
        }

        // Fall back to the raw cookie value, which is what a same-origin client (the Vite dev
        // proxy) reads from the readable XSRF-TOKEN cookie and sends back verbatim.
        return safeResolve(this.plain, request, csrfToken);
    }

    private String safeResolve(CsrfTokenRequestHandler handler,
                               HttpServletRequest request,
                               CsrfToken csrfToken) {
        try {
            return handler.resolveCsrfTokenValue(request, csrfToken);
        } catch (RuntimeException e) {
            // A malformed/unmaskable value must mean "no match", never a 500.
            return null;
        }
    }
}
