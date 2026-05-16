package com.wallet.entity;

import com.wallet.enums.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, unique = true, length = 15)
    private String phoneNumber;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String pin;

    // Authorization role (issue #2). Stored as a string; existing rows
    // default to USER via the columnDefinition so a ddl-auto=update
    // against a populated table doesn't fail on a NOT NULL add.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'USER'")
    @Builder.Default
    private Role role = Role.USER;

    private int failedPinAttempts;

    private LocalDateTime pinLockedUntil;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Wallet wallet;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        failedPinAttempts = 0;
        if (role == null) {
            role = Role.USER;
        }
    }

    public boolean isPinLocked() {
        return pinLockedUntil != null && LocalDateTime.now().isBefore(pinLockedUntil);
    }
}
