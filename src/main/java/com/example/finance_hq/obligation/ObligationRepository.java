package com.example.finance_hq.obligation;

import com.example.finance_hq.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObligationRepository extends JpaRepository<Obligation, UUID> {

    Page<Obligation> findAllByUser(User user, Pageable pageable);

    Optional<Obligation> findByIdAndUser(UUID id, User user);

    @Query("SELECT o FROM Obligation o JOIN FETCH o.user")
    List<Obligation> findAllWithUser();
}
