package com.wallet.service;

import com.wallet.entity.User;
import com.wallet.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Owns the failed-PIN counter in its OWN transaction (issue #9).
 *
 * <p>PIN validation happens inside the @Transactional withdraw/transfer
 * methods. When the PIN is wrong those methods throw, which rolls the
 * whole transaction back — including the counter increment, so the
 * lockout never triggered. Incrementing in a REQUIRES_NEW transaction
 * commits the counter independently of the (rolled-back) caller.
 *
 * <p>This is a separate bean so the REQUIRES_NEW boundary is crossed via
 * a real Spring proxy (self-invocation would not start a new tx).
 */
@Service
@RequiredArgsConstructor
public class PinAttemptService {

    static final int MAX_PIN_ATTEMPTS = 3;
    static final int LOCKOUT_MINUTES = 15;

    private final UserRepository userRepository;

    /** Outcome of recording a failed PIN attempt. */
    public record FailureResult(int attempts, boolean locked) {}

    /**
     * Increment the failed-PIN counter and, on the Nth failure, set the
     * lock window. Commits in its own transaction so it survives the
     * caller's rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult recordFailure(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setFailedPinAttempts(user.getFailedPinAttempts() + 1);
        boolean locked = user.getFailedPinAttempts() >= MAX_PIN_ATTEMPTS;
        if (locked) {
            user.setPinLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
        }
        userRepository.save(user);
        return new FailureResult(user.getFailedPinAttempts(), locked);
    }

    /**
     * Clear the counter + lock after a successful PIN. Own transaction so
     * a subsequent failure in the caller doesn't roll the reset back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.getFailedPinAttempts() != 0 || user.getPinLockedUntil() != null) {
            user.setFailedPinAttempts(0);
            user.setPinLockedUntil(null);
            userRepository.save(user);
        }
    }

    /**
     * Fresh lock check against the independently-committed counter, not
     * the possibly-stale entity loaded in the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isLocked(Long userId) {
        return userRepository.findById(userId)
                .map(User::isPinLocked)
                .orElse(false);
    }
}
