package com.momocircle.repository.momoride;
import com.momocircle.domain.momoride.Ride; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import java.time.LocalDateTime; import java.util.*;
public interface RideRepository extends JpaRepository<Ride,String>{
 List<Ride> findByStatusAndOriginContainingIgnoreCaseAndDestinationContainingIgnoreCaseAndDepartureTimeBetweenOrderByDepartureTimeAsc(Ride.Status status,String origin,String destination,LocalDateTime from,LocalDateTime to);
 List<Ride> findByDriverIdOrderByDepartureTimeDesc(String driverId);
 Page<Ride> findAllByOrderByDepartureTimeDesc(Pageable pageable);
}
