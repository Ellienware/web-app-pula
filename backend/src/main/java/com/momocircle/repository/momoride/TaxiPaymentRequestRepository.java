package com.momocircle.repository.momoride;
import com.momocircle.domain.momoride.TaxiPaymentRequest; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import java.time.Instant; import java.util.*;
public interface TaxiPaymentRequestRepository extends JpaRepository<TaxiPaymentRequest,String>{
 List<TaxiPaymentRequest> findByPassengerIdOrderByCreatedAtDesc(String id);
 List<TaxiPaymentRequest> findByDriverIdOrderByCreatedAtDesc(String id);
 List<TaxiPaymentRequest> findByStatusAndExpiresAtBefore(TaxiPaymentRequest.Status status, Instant cutoff);
 Page<TaxiPaymentRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
