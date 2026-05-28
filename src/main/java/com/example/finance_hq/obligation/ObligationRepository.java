package com.example.finance_hq.obligation;

import com.example.finance_hq.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObligationRepository extends JpaRepository<Obligation, Long> {

    List<Obligation> findAllByUserOrderByCreatedAtDesc(User user);

    Optional<Obligation> findByIdAndUser(Long id, User user);
}
