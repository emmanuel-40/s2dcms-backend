package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.Arrays;
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

    private static final Logger logger = LoggerFactory.getLogger(SpaCsrfTokenRequestHandler.class);

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
        // Try the masked form first (the value this handler publishes). A miss is safe and
        // simply falls through to the raw comparison below.
        String masked = safeResolve(this.xor, request, csrfToken);
        if (masked != null) {
            return masked;
        }

        // Fall back to the raw cookie value, which is what a same-origin client (the Vite dev
        // proxy) reads from the readable XSRF-TOKEN cookie and sends back verbatim.
        String raw = safeResolve(this.plain, request, csrfToken);
        if (raw == null) {
            // Neither form validated. Log the SHAPE of the failure only - never the value - so
            // the next deploy says whether the header was absent, was the raw cookie value, or
            // was a stale masked value. That distinction is what identifies the cause; the
            // generic "CSRF token missing or invalid" response cannot.
            String header = request.getHeader(csrfToken.getHeaderName());
            String cookie = readCookie(request, csrfToken.getParameterName());
            logger.warn("CSRF mismatch on {} - headerPresent={}, headerLen={}, cookiePresent={}, "
                            + "expectedLen={}, looksMasked={}",
                    request.getRequestURI(),
                    StringUtils.hasText(header),
                    header == null ? 0 : header.length(),
                    StringUtils.hasText(cookie),
                    csrfToken.getToken() == null ? 0 : csrfToken.getToken().length(),
                    header != null && header.length() > (csrfToken.getToken() == null
                            ? 0 : csrfToken.getToken().length()));
        }
        return raw;
    }

    private String readCookie(HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
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
