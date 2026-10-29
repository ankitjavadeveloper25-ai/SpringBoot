package com.winterarc.server.repository;

import com.winterarc.server.model.PromoCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PromoCodeRepository extends JpaRepository<PromoCode, String> {

    Optional<PromoCode> findByCode(String code);

    boolean existsByCode(String code);

    Page<PromoCode> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
