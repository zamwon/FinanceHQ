package com.example.finance_hq.obligation;

import com.example.finance_hq.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All obligation lookups by ID must be scoped to the authenticated user.
 * {@link #findById(UUID)} is intentionally disabled — use {@link #findByIdAndUser(UUID, User)} instead.
 */
public interface ObligationRepository extends JpaRepository<Obligation, UUID> {

    // NOTE: deleteById() also delegates through findById() internally — it will throw here too.
    // Always call repository.delete(entity) after fetching via findByIdAndUser(); never deleteById().
    @Override
    default Optional<Obligation> findById(UUID id) {
        throw new UnsupportedOperationException(
                "Direct ID lookup bypasses ownership. Use findByIdAndUser(UUID, User) instead.");
    }

    Page<Obligation> findAllByUser(User user, Pageable pageable);

    Optional<Obligation> findByIdAndUser(UUID id, User user);

    @Query("SELECT o FROM Obligation o JOIN FETCH o.user")
    List<Obligation> findAllWithUser();
}
