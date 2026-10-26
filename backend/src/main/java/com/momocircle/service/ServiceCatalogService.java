package com.momocircle.service;

import com.momocircle.domain.ServiceCategory;
import com.momocircle.domain.User;
import com.momocircle.dto.ServiceDtos.CategoryRequest;
import com.momocircle.dto.ServiceDtos.CategoryView;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ServiceCategoryRepository;
import com.momocircle.services.ServiceInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Admin-managed service categories. Nothing in code depends on specific rows; the seed data is just a starting point. */
@Service
public class ServiceCatalogService {

    private final ServiceCategoryRepository categories;
    private final AuditLogService audit;

    public ServiceCatalogService(ServiceCategoryRepository categories, AuditLogService audit) {
        this.categories = categories;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> listActive() {
        return categories.findByActiveTrueOrderBySortOrderAscNameAsc().stream().map(ServiceViews::category).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryView> listAll(User admin) {
        return categories.findAllByOrderBySortOrderAscNameAsc().stream().map(ServiceViews::category).toList();
    }

    @Transactional
    public CategoryView create(User admin, CategoryRequest req) {
        ServiceCategory c = new ServiceCategory();
        apply(c, req, true);
        c = categories.save(c);
        audit.record(admin, "SERVICE_CATEGORY_CREATED", "SERVICE_CATEGORY", c.getId(), c.getName());
        return ServiceViews.category(c);
    }

    @Transactional
    public CategoryView update(User admin, String id, CategoryRequest req) {
        ServiceCategory c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category not found"));
        apply(c, req, false);
        c = categories.save(c);
        audit.record(admin, "SERVICE_CATEGORY_UPDATED", "SERVICE_CATEGORY", c.getId(), c.getName() + " active=" + c.isActive());
        return ServiceViews.category(c);
    }

    private void apply(ServiceCategory c, CategoryRequest req, boolean creating) {
        try {
            String name = ServiceInput.text(req.name(), 80, "Name", true);
            String slug = req.slug() == null || req.slug().isBlank() ? ServiceInput.slug(name) : ServiceInput.slug(req.slug());
            var existing = categories.findBySlug(slug);
            if (existing.isPresent() && (creating || !existing.get().getId().equals(c.getId()))) {
                throw ApiException.conflict("A category with that name already exists");
            }
            ServiceCategory parent = null;
            if (req.parentId() != null && !req.parentId().isBlank()) {
                parent = categories.findById(req.parentId()).orElseThrow(() -> ApiException.badRequest("Parent category not found"));
                if (parent.getParent() != null) {
                    throw ApiException.badRequest("Categories can only be two levels deep");
                }
                if (!creating && parent.getId().equals(c.getId())) {
                    throw ApiException.badRequest("A category cannot be its own parent");
                }
            }
            c.setName(name);
            c.setSlug(slug);
            c.setParent(parent);
            if (req.sortOrder() != null) c.setSortOrder(req.sortOrder());
            if (req.active() != null) c.setActive(req.active());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
