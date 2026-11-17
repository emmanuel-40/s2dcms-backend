package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.RefreshTokenException;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.RefreshToken;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class RefreshTokenService {

        private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public RefreshToken createRefreshTokenForStudent(Student student) {
            RefreshToken token = new RefreshToken();
            token.setToken(UUID.randomUUID().toString());
            token.setExpiryDate(Instant.now().plus(24, ChronoUnit.HOURS));
            token.setStudent(student);
            return refreshTokenRepository.save(token);
        }

    public RefreshToken createRefreshTokenForDepartment(Department department) {
        RefreshToken token = new RefreshToken();
        token.setToken(UUID.randomUUID().toString());
        token.setExpiryDate(Instant.now().plus(24, ChronoUnit.HOURS));
        token.setDepartment(department);
        return refreshTokenRepository.save(token);
    }

    @Transactional
    public RefreshToken rotateToken(String tokenValue) {
        RefreshToken oldToken = refreshTokenRepository.findByTokenForUpdate(tokenValue)
                .orElseThrow(() -> new RefreshTokenException("Invalid refresh token"));

        if (oldToken.isRevoked()) {
            throw new RefreshTokenException("Refresh token revoked");
        }

        if (oldToken.getExpiryDate().isBefore(Instant.now())) {
            throw new RefreshTokenException("Refresh token expired");
        }

        oldToken.setRevoked(true);
        refreshTokenRepository.save(oldToken);

        RefreshToken newToken = new RefreshToken();
        newToken.setToken(UUID.randomUUID().toString());
        newToken.setExpiryDate(Instant.now().plus(24, ChronoUnit.HOURS));
        newToken.setStudent(oldToken.getStudent());
        newToken.setDepartment(oldToken.getDepartment());
        return refreshTokenRepository.save(newToken);
    }

    @Transactional
    public void revokeToken(String token) {
        refreshTokenRepository.findByTokenForUpdate(token)
                .ifPresent(rt -> {
                    rt.setRevoked(true);
                    refreshTokenRepository.save(rt);
                });
    }
}
