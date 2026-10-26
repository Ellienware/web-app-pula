package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_categories")
public class ServiceCategory extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id")
    private ServiceCategory parent;
    @Column(nullable = false, length = 80)
    private String name;
    @Column(nullable = false, length = 80, unique = true)
    private String slug;
    @Column(nullable = false)
    private int sortOrder = 0;
    @Column(nullable = false)
    private boolean active = true;

    public ServiceCategory getParent() { return parent; }
    public void setParent(ServiceCategory parent) { this.parent = parent; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
