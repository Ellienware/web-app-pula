CREATE TABLE wholesale_product_image_urls (
    wholesale_product_id VARCHAR(36) NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    CONSTRAINT fk_wholesale_product_image_urls_product
        FOREIGN KEY (wholesale_product_id)
        REFERENCES wholesale_products(id)
        ON DELETE CASCADE
);
