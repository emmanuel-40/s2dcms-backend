package com.myproject.S2dcms.securityConfig;

import com.myproject.S2dcms.model.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access.expiration}") // 15 mins
    private long accessTokenExpiration;

    public String generateToken(String subject, Role role) {
        return Jwts.builder()
                .setSubject(subject)
                .claim("role", role.name())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(
                        Keys.hmacShaKeyFor(jwtSecret.getBytes()),
                        io.jsonwebtoken.SignatureAlgorithm.HS256
                )
                .compact();
    }

    public String getUsername(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(jwtSecret.getBytes())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public String getRole(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(jwtSecret.getBytes())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("role", String.class);
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(jwtSecret.getBytes())
                    .build()
                    .parseClaimsJws(token);

            return true;

        } catch (ExpiredJwtException e) {
            logger.debug("JWT validation failed: expired");
        } catch (MalformedJwtException e) {
            logger.debug("JWT validation failed: malformed");
        } catch (UnsupportedJwtException e) {
            logger.debug("JWT validation failed: unsupported");
        } catch (IllegalArgumentException e) {
            logger.debug("JWT validation failed: empty/invalid");
        } catch (JwtException e) {
            logger.debug("JWT validation failed");
        }

        return false;
    }
}
