package com.parkview.ruleengine.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only view of {@code identity.v_user_permissions}, the published view of the permissions a user holds through the access roles (authentication-service,
 * ADR 0010, 0022 D3): one row per user and permission. rule-service reads it to resolve the permissions of the caller when {@code parkview.permissions.source=directory}
 * ({@code ReadModelPermissionDirectory}). Maps just the columns used; never written by this service and never created by its Liquibase changelog.
 */
@Entity
@Immutable
@IdClass(UserPermissionView.Key.class)
@Table(schema = "identity", name = "v_user_permissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPermissionView {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "permission")
    private String permission;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UserPermissionView other && userId != null
                && Objects.equals(userId, other.userId) && Objects.equals(permission, other.permission));
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, permission);
    }

    /** The composite key: a user has a permission at most once. */
    public record Key(UUID userId, String permission) implements Serializable {
    }
}
