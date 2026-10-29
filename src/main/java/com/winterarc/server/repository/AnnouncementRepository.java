package com.winterarc.server.repository;

import com.winterarc.server.model.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, String> {

    List<Announcement> findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(Instant now);

    Page<Announcement> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
