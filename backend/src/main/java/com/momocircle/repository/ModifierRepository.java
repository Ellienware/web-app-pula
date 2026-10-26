package com.momocircle.repository;

import com.momocircle.domain.Modifier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierRepository extends JpaRepository<Modifier, String> {
    List<Modifier> findByGroupIdOrderByNameAsc(String groupId);
}
