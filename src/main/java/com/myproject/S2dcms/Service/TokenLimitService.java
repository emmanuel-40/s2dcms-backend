package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.RefreshToken;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TokenLimitService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService tokenService;

    public TokenLimitService(RefreshTokenRepository refreshTokenRepository, RefreshTokenService tokenService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenService = tokenService;
    }

    public void manageTokenLimitForStudent(Student student) {

        List<RefreshToken> newest = refreshTokenRepository.findTop5ByStudentOrderByCreatedAtDesc(student);
        List<Long> newestIds = newest.stream().map(RefreshToken::getId).collect(Collectors.toList());

        if (newestIds.size() >= 5) {
            refreshTokenRepository.revokeAllExceptNewestForStudent(student, newestIds);
        }
    }

    public void manageTokenLimitForDepartment(Department department) {

        List<RefreshToken> newest = refreshTokenRepository.findTop5ByDepartmentOrderByCreatedAtDesc(department);
        List<Long> newestIds = newest.stream().map(RefreshToken::getId).collect(Collectors.toList());

        if (newestIds.size() >= 5) {
            refreshTokenRepository.revokeAllExceptNewestForDepartment(department, newestIds);
        }
    }
}
