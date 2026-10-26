package com.momocircle.repository.momoride;
import com.momocircle.domain.momoride.Driver; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import java.util.Optional;
public interface DriverRepository extends JpaRepository<Driver,String>{
 Optional<Driver> findByUserId(String userId);
 Page<Driver> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
