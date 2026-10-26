package com.momocircle.service;

import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.enums.RecurringFrequency;
import com.momocircle.domain.enums.StokvelType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Discover query. Only DISCOVERABLE stokvels can ever be returned: that predicate is part of the fixed base query, not an
 * optional filter. Optional filters are added only when present (bound parameters, no string concatenation of values), and
 * people who are already members are excluded.
 */
@Repository
public class StokvelDiscoveryQuery {

    @PersistenceContext
    private EntityManager em;

    public record Filter(String text, StokvelType type, RecurringFrequency frequency, BigDecimal minContribution,
                         BigDecimal maxContribution, String area) { }

    @Transactional(readOnly = true)
    public Page<StokvelProfile> search(String viewerUserId, Filter f, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where p.discoverable = true"
                + " and not exists (select 1 from CircleMembership m where m.circle = p.circle and m.user.id = :viewer)");
        if (f.text() != null) where.append(" and (lower(p.circle.name) like :text escape '!' or lower(p.publicSummary) like :text escape '!'"
                + " or lower(p.area) like :text escape '!')");
        if (f.type() != null) where.append(" and p.stokvelType = :type");
        if (f.frequency() != null) where.append(" and p.contributionFrequency = :frequency");
        if (f.minContribution() != null) where.append(" and p.contributionAmount >= :min");
        if (f.maxContribution() != null) where.append(" and p.contributionAmount <= :max");
        if (f.area() != null) where.append(" and lower(p.area) like :area escape '!'");

        TypedQuery<StokvelProfile> q = em.createQuery(
                "select p from StokvelProfile p" + where + " order by p.createdAt desc, p.id desc", StokvelProfile.class);
        TypedQuery<Long> c = em.createQuery("select count(p) from StokvelProfile p" + where, Long.class);
        for (TypedQuery<?> query : List.of(q, c)) {
            query.setParameter("viewer", viewerUserId);
            if (f.text() != null) query.setParameter("text", like(f.text()));
            if (f.type() != null) query.setParameter("type", f.type());
            if (f.frequency() != null) query.setParameter("frequency", f.frequency());
            if (f.minContribution() != null) query.setParameter("min", f.minContribution());
            if (f.maxContribution() != null) query.setParameter("max", f.maxContribution());
            if (f.area() != null) query.setParameter("area", like(f.area()));
        }
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(q.getResultList(), PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()), c.getSingleResult());
    }

    /** "%term%" with LIKE wildcards in the user's text escaped, lower-cased. */
    static String like(String raw) {
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + t + "%";
    }
}
