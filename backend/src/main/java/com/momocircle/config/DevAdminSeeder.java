package com.momocircle.config;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.PlatformRole;
import com.momocircle.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Local/demo convenience only: guarantees at least one ADMIN account exists so
 * the admin portal is reachable without a manual SQL step. Runs on the "dev"
 * profile alone (in-memory H2) — never on "prod", where the first admin must
 * be promoted deliberately (e.g. directly in the database) instead of via a
 * hard-coded bootstrap account.
 */
@Configuration
@Profile("dev")
public class DevAdminSeeder {

    @Bean
    CommandLineRunner seedAdmin(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.seed.admin-email:admin@momocircle.dev}") String adminEmail,
                                 @Value("${app.seed.admin-password:ChangeMe123!}") String adminPassword) {
        return args -> {
            if (userRepository.existsByEmail(adminEmail)) {
                return;
            }
            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setFullName("Platform Admin");
            admin.setPhoneNumber("0000000000");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setRole(PlatformRole.ADMIN);
            userRepository.save(admin);
        };
    }
}
