package com.parkview.ruleengine.client.jpa;

import com.parkview.platform.domain.Money;
import com.parkview.ruleengine.client.SiteDirectory;
import com.parkview.ruleengine.domain.value.ZoneInfo;
import com.parkview.ruleengine.readmodel.SiteView;
import com.parkview.ruleengine.readmodel.SiteViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
class JpaSiteDirectory implements SiteDirectory {

    private final SiteViewRepository sites;

    @Override
    public Optional<String> addressOf(String siteId) {
        return siteId == null ? Optional.empty()
                : sites.findById(siteId).map(SiteView::getAddress).filter(a -> a != null && !a.isBlank());
    }

    @Override
    public Optional<String> currencyOf(String siteId) {
        if (siteId == null) {
            return Optional.empty();
        }
        String raw = sites.findById(siteId).map(SiteView::getCurrency).orElse(null);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Money.requireCurrency(raw));
        } catch (IllegalArgumentException e) {
            log.error("Site {} has the currency '{}' which is not an ISO 4217 code", siteId, raw);
            return Optional.empty();
        }
    }

    @Override
    public Optional<ZoneInfo> infoOf(String siteId) {
        return siteId == null ? Optional.empty()
                : sites.findById(siteId).map(s -> ZoneInfo.ofSite(s.getAddress(), s.getMaxMinutes(), s.getCountry(), s.getTimezone(),
                        s.isAllowsParking()));
    }
}
