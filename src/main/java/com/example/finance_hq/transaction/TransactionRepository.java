package com.example.finance_hq.transaction;

import com.example.finance_hq.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Override
    default Optional<Transaction> findById(UUID id) {
        throw new UnsupportedOperationException(
                "Direct ID lookup bypasses ownership. Use findByIdAndUser(UUID, User) instead.");
    }

    Page<Transaction> findAllByUser(User user, Pageable pageable);

    Optional<Transaction> findByIdAndUser(UUID id, User user);
}
