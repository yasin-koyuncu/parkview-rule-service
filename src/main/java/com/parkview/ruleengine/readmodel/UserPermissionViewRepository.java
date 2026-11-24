package com.parkview.ruleengine.readmodel;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Read-only access to {@link UserPermissionView}; deliberately not a CrudRepository, so there is no save or delete. */
@Transactional(readOnly = true)
public interface UserPermissionViewRepository extends Repository<UserPermissionView, UserPermissionView.Key> {

    /** Every permission a user holds. */
    List<UserPermissionView> findByUserId(UUID userId);
}
