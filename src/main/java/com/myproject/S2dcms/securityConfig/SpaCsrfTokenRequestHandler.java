package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * SPA-compatible CSRF handler.
 *
 * Spring Security 6 defaults to XOR-masked tokens. Browser JS reads the raw
 * XSRF-TOKEN cookie and sends it as X-XSRF-TOKEN. When that header is present,
 * resolve against the raw cookie value so cookie auth + CSRF works for SPAs.
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
        // Render the RAW token into the request attribute: CsrfCookieFilter copies it into
        // the CORS-exposed X-XSRF-TOKEN response header and GET /api/auth/csrf serves it in
        // its body, and resolveCsrfTokenValue below accepts exactly that raw value. An
        // XOR-masked rendering would make both channels hand the SPA a value this very
        // handler then rejects on submission.
        this.plain.handle(request, response, csrfToken);
        // Force deferred token load so CookieCsrfTokenRepository can write the cookie
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor)
                .resolveCsrfTokenValue(request, csrfToken);
    }
}
