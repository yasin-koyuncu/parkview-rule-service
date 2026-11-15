package com.parkview.ruleengine.service.violation;

import com.parkview.platform.web.ResourceNotFoundException;
import com.parkview.ruleengine.domain.Violation;
import com.parkview.ruleengine.repository.ViolationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Reads of violations for the controllers. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ViolationQueryService {

    private final ViolationRepository violations;

    public Violation get(UUID id) {
        return violations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Violation", id));
    }

    public Page<Violation> activeByZone(String zoneId, Pageable pageable) {
        return violations.findActiveByZone(zoneId, pageable);
    }

    /** The active violations of the site, in its zones and outside them. */
    public Page<Violation> activeBySite(String siteId, Pageable pageable) {
        return violations.findActiveBySite(siteId, pageable);
    }

    public Page<Violation> byPlate(String plate, Pageable pageable) {
        return violations.findByPlate(plate, pageable);
    }

    /** Same, limited to the given sites (an operator). No sites, no violations. */
    public Page<Violation> byPlateInSites(String plate, List<String> siteIds, Pageable pageable) {
        if (siteIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return violations.findByPlateAndSiteIdIn(plate, siteIds, pageable);
    }
}
