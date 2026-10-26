package com.momocircle.repository.momoride;
import com.momocircle.domain.momoride.RideBooking; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface RideBookingRepository extends JpaRepository<RideBooking,String>{
 Optional<RideBooking> findByRideIdAndPassengerId(String rideId,String passengerId);
 List<RideBooking> findByPassengerIdOrderByCreatedAtDesc(String passengerId);
 List<RideBooking> findByRideId(String rideId);
 long countByRideIdAndStatus(String rideId,RideBooking.Status status);
 Page<RideBooking> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
