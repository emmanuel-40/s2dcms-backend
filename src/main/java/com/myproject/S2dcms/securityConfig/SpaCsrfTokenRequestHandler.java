package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

/**
 * SPA-compatible CSRF handler: the token is published XOR-masked and only the masked form is
 * accepted back.
 *
 * <p>Spring Security 6 masks CSRF tokens by default (BREACH mitigation). Masking randomises the
 * published value on every response, so there is no stable plaintext for an attacker to grind out
 * of a compressed response by injecting chosen text and measuring sizes.
 *
 * <p>A browser SPA cannot read the token from {@code document.cookie} when it is served from a
 * different origin than the API - which is exactly the deployed setup here - so the backend also
 * hands the token over in the CORS-exposed {@code X-XSRF-TOKEN} header and in the JSON body of
 * {@code GET /api/auth/csrf}. Both of those carry the same masked value the client then replays.
 *
 * <p>The invariant that matters: whatever {@link #handle} publishes, {@link #resolveCsrfTokenValue}
 * must accept. Publishing masked while resolving raw - the original bug - puts two different
 * representations of one secret on the wire and rejects every write.
 *
 * <p>Note on scope: masking defends against BREACH, a compression side-channel. It is not a
 * defence against XSS - the token is deliberately readable by JavaScript, so script running on
 * this origin can read it either way. Stealing the session is prevented by keeping the auth
 * tokens {@code HttpOnly}, which never reach JavaScript at all.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    // Publish the token XOR-masked, and accept only the masked form back.
    //
    // The mask randomises the published value on every response. That is what defeats BREACH:
    // an attacker who can inject chosen text into a compressed response and measure its size
    // can otherwise recover a secret byte-by-byte from how well it compresses. With a fresh
    // mask per response there is no stable plaintext to grind out.
    //
    // Publishing and resolving the SAME form is the part that must not drift. An earlier
    // version published masked and resolved raw - two representations of one secret that were
    // never in agreement, so every write was rejected. Both halves now use the masked handler.
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken
    ) {
        // Publish the MASKED token into the request attribute. CsrfCookieFilter copies it into the
        // CORS-exposed X-XSRF-TOKEN header, and GET /api/auth/csrf serves the same masked value in
        // its body, so the SPA echoes back exactly what it received.
        this.xor.handle(request, response, csrfToken);
        // Force deferred token load so CookieCsrfTokenRepository can write the cookie
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        // Accept only the masked form: it is the only form this handler ever publishes, so
        // there is exactly one representation in play between client and server.
        return this.xor.resolveCsrfTokenValue(request, csrfToken);
    }
}
