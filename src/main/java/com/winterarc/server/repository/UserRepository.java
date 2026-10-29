package com.winterarc.server.repository;

import com.winterarc.server.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE " +
           "(:query IS NULL OR :query = '' OR LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "(:role IS NULL OR :role = '' OR u.role = :role) AND " +
           "(:status IS NULL OR :status = '' OR u.status = :status)")
    Page<User> searchUsers(@Param("query") String query,
                           @Param("role") String role,
                           @Param("status") String status,
                           Pageable pageable);

    long countByStatus(String status);
    long countByRole(String role);
    long countByCreatedAtAfter(Instant after);
}
