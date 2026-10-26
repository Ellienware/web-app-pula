package com.momocircle.repository;

import com.momocircle.domain.Circle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CircleRepository extends JpaRepository<Circle, String> {
}
