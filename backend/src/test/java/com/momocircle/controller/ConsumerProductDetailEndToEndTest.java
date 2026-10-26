
package com.momocircle.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:consumerproducte2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
class ConsumerProductDetailEndToEndTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void consumerDetailIsPublicAndExcludesWholesaleOnlyProducts() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        String consumerBusiness = createBusiness(suffix, "CONSUMER");
        String wholesaleBusiness = createBusiness(suffix, "WHOLESALE");
        String dualBusiness = createBusiness(suffix, "DUAL");

        capability(consumerBusiness, "CAN_SELL_TO_CONSUMERS");
        capability(wholesaleBusiness, "CAN_SUPPLY_BUSINESSES");
        capability(dualBusiness, "CAN_SUPPLY_BUSINESSES");
        capability(dualBusiness, "CAN_SELL_TO_CONSUMERS");

        String consumerProduct = createProduct(consumerBusiness, suffix);
        String wholesaleProduct = createProduct(wholesaleBusiness, suffix);
        String dualProduct = createProduct(dualBusiness, suffix);

        // No Authorization header: consumer product detail is public.
        assertEquals(200, mvc.perform(get("/api/market/products/" + consumerProduct))
                .andReturn().getResponse().getStatus());

        assertEquals(404, mvc.perform(get("/api/market/products/" + wholesaleProduct))
                .andReturn().getResponse().getStatus());

        assertEquals(200, mvc.perform(get("/api/market/products/" + dualProduct))
                .andReturn().getResponse().getStatus());

        // The general product-detail endpoint remains backward-compatible.
        assertEquals(200, mvc.perform(get("/api/products/" + wholesaleProduct))
                .andReturn().getResponse().getStatus());
    }

    private String createBusiness(String suffix, String type) {
        String owner = UUID.randomUUID().toString();
        String business = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update(
            "insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            owner, now, now, owner + "@consumer-test.local",
            "Consumer Test", "+2782" + String.format("%07d", Math.abs(owner.hashCode()) % 10000000),
            "test-hash", "USER", true);

        jdbc.update(
            "insert into businesses (id, created_at, updated_at, owner_id, name, category, active, rating_average, rating_count, business_type, registration_status) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            business, now, now, owner, type + " Business " + suffix, "General",
            true, BigDecimal.ZERO, 0, "LOCAL_BUSINESS", "NOT_REGISTERED");

        return business;
    }

    private void capability(String business, String value) {
        jdbc.update(
            "insert into business_capabilities (business_id, capability) values (?, ?)",
            business, value);
    }

    private String createProduct(String business, String suffix) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update(
            "insert into products (id, created_at, updated_at, business_id, name, category, price, currency, is_service, stock, status, rating_average, rating_count, match_classification) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, now, now, business, "Consumer Test Product " + suffix,
            "Regression", new BigDecimal("10.00"), "ZAR", false, 100,
            "ACTIVE", BigDecimal.ZERO, 0, "UNIQUE_PRODUCT");

        return id;
    }
}
