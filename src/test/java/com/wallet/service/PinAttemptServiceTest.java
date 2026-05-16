package com.wallet.service;

import com.wallet.entity.User;
import com.wallet.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused coverage for the issue #9 fix. PinAttemptService runs each
 * method in a REQUIRES_NEW transaction, so this test is NOT
 * @Transactional — fixtures are committed and truncated per test
 * (mirrors WalletServiceTest).
 */
@SpringBootTest
class PinAttemptServiceTest {

    @Autowired private PinAttemptService pinAttemptService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String t : new String[]{
                "audit_logs", "ledger_entries", "transactions", "wallets", "users"}) {
            jdbcTemplate.execute("TRUNCATE TABLE " + t);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");

        User u = userRepository.save(User.builder()
                .fullName("Pin Tester")
                .email("pin-test@local")
                .phoneNumber("+254700000099")
                .password("x")
                .pin("hashed")
                .build());
        userId = u.getId();
    }

    @Test
    void recordFailure_incrementsCounter_andDoesNotLockBeforeThreshold() {
        PinAttemptService.FailureResult r1 = pinAttemptService.recordFailure(userId);
        assertThat(r1.attempts()).isEqualTo(1);
        assertThat(r1.locked()).isFalse();

        PinAttemptService.FailureResult r2 = pinAttemptService.recordFailure(userId);
        assertThat(r2.attempts()).isEqualTo(2);
        assertThat(r2.locked()).isFalse();

        assertThat(pinAttemptService.isLocked(userId)).isFalse();
        assertThat(userRepository.findById(userId).orElseThrow().getPinLockedUntil())
                .isNull();
    }

    @Test
    void recordFailure_locksExactlyOnThirdAttempt() {
        pinAttemptService.recordFailure(userId);
        pinAttemptService.recordFailure(userId);
        PinAttemptService.FailureResult third = pinAttemptService.recordFailure(userId);

        assertThat(third.attempts()).isEqualTo(3);
        assertThat(third.locked()).isTrue();
        assertThat(pinAttemptService.isLocked(userId)).isTrue();

        User locked = userRepository.findById(userId).orElseThrow();
        assertThat(locked.getPinLockedUntil()).isNotNull();
        assertThat(locked.isPinLocked()).isTrue();
    }

    @Test
    void reset_clearsCounterAndLock() {
        pinAttemptService.recordFailure(userId);
        pinAttemptService.recordFailure(userId);
        pinAttemptService.recordFailure(userId);
        assertThat(pinAttemptService.isLocked(userId)).isTrue();

        pinAttemptService.reset(userId);

        User cleared = userRepository.findById(userId).orElseThrow();
        assertThat(cleared.getFailedPinAttempts()).isZero();
        assertThat(cleared.getPinLockedUntil()).isNull();
        assertThat(pinAttemptService.isLocked(userId)).isFalse();
    }

    @Test
    void reset_isNoOpWhenNothingToClear() {
        pinAttemptService.reset(userId);   // no prior failures
        User u = userRepository.findById(userId).orElseThrow();
        assertThat(u.getFailedPinAttempts()).isZero();
        assertThat(u.getPinLockedUntil()).isNull();
    }

    @Test
    void isLocked_falseForUnknownUser_andMissingUserThrowsOnMutation() {
        assertThat(pinAttemptService.isLocked(999_999L)).isFalse();
        assertThatThrownBy(() -> pinAttemptService.recordFailure(999_999L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pinAttemptService.reset(999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
