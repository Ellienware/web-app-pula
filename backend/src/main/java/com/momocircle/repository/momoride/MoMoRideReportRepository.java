package com.momocircle.repository.momoride;
import com.momocircle.domain.momoride.MoMoRideReport; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository;
public interface MoMoRideReportRepository extends JpaRepository<MoMoRideReport,String>{
 Page<MoMoRideReport> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
