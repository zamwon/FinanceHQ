package com.example.finance_hq.obligation;

import com.example.finance_hq.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObligationRepository extends JpaRepository<Obligation, UUID> {

    List<Obligation> findAllByUserOrderByCreatedAtDesc(User user);

    Optional<Obligation> findByIdAndUser(UUID id, User user);
}
