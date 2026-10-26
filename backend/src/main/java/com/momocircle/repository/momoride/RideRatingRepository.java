package com.momocircle.repository.momoride;
import com.momocircle.domain.momoride.RideRating; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface RideRatingRepository extends JpaRepository<RideRating,String>{
 boolean existsByRideIdAndRaterId(String rideId, String raterId);
 List<RideRating> findByDriverIdOrderByCreatedAtDesc(String driverId);
}
