package com.wallet.repository;

import com.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    @Query("SELECT w FROM Wallet w JOIN w.user u WHERE u.phoneNumber = :phone")
    Optional<Wallet> findByUserPhoneNumber(@Param("phone") String phoneNumber);
}
