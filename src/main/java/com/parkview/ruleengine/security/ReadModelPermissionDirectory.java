package com.parkview.ruleengine.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.platform.domain.Uuids;
import com.parkview.platform.security.PermissionClaims;
import com.parkview.platform.security.PermissionDirectory;
import com.parkview.platform.security.PermissionsUnavailableException;
import com.parkview.platform.security.servlet.PermissionsProperties;
import com.parkview.ruleengine.readmodel.UserPermissionView;
import com.parkview.ruleengine.readmodel.UserPermissionViewRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link PermissionDirectory} over the published view {@code identity.v_user_permissions} ({@link UserPermissionView}), cached per user for
 * {@code parkview.permissions.cache-ttl}: no entry outlives it, so a change of role applies within that time. Used only with
 * {@code parkview.permissions.source=directory} (ADR 0022 D3). A database that does not answer is a {@link PermissionsUnavailableException}: the request is refused (503),
 * never allowed and never treated as "no permissions". Only {@code resource:action} is ever granted, as for the claim.
 */
@Component
public class ReadModelPermissionDirectory implements PermissionDirectory {

    private final UserPermissionViewRepository permissions;
    private final Cache<UUID, Set<String>> cache;

    @Autowired
    public ReadModelPermissionDirectory(UserPermissionViewRepository permissions, PermissionsProperties properties) {
        this(permissions, properties, Ticker.systemTicker());
    }

    ReadModelPermissionDirectory(UserPermissionViewRepository permissions, PermissionsProperties properties, Ticker ticker) {
        this.permissions = permissions;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.cacheMax())
                .expireAfterWrite(properties.cacheTtl())
                .ticker(ticker)
                .build();
    }

    @Override
    public Set<String> permissionsOf(String userId) {
        UUID id = Uuids.parseOrNull(userId);
        if (id == null) {
            return Set.of();
        }
        try {
            return cache.get(id, this::load);
        } catch (DataAccessException e) {
            throw new PermissionsUnavailableException("Permissions of the user cannot be determined", e);
        }
    }

    private Set<String> load(UUID userId) {
        Set<String> granted = PermissionClaims.parse(permissions.findByUserId(userId).stream().map(UserPermissionView::getPermission).toList());
        return Collections.unmodifiableSortedSet(new TreeSet<>(granted));
    }
}
