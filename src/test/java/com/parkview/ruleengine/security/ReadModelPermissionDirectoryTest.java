package com.parkview.ruleengine.security;

import com.github.benmanes.caffeine.cache.Ticker;
import com.parkview.platform.security.PermissionsUnavailableException;
import com.parkview.platform.security.servlet.PermissionsProperties;
import com.parkview.ruleengine.readmodel.UserPermissionView;
import com.parkview.ruleengine.readmodel.UserPermissionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR 0022 D3: the permissions of the caller from the published view, cached for the cache time. */
class ReadModelPermissionDirectoryTest {

    private static final UUID USER = UUID.fromString("7d0c2a56-6b53-4d4e-9a0c-0f9d1f4f4e11");

    private final UserPermissionViewRepository repository = mock(UserPermissionViewRepository.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final ReadModelPermissionDirectory directory = new ReadModelPermissionDirectory(repository,
            new PermissionsProperties(PermissionsProperties.Source.DIRECTORY, Duration.ofSeconds(30), 100), ticker);

    private static UserPermissionView view(String permission) {
        UserPermissionView view = BeanUtils.instantiateClass(UserPermissionView.class);
        ReflectionTestUtils.setField(view, "userId", USER);
        ReflectionTestUtils.setField(view, "permission", permission);
        return view;
    }

    @Test
    void returnsThePermissionsOfTheUserSorted() {
        when(repository.findByUserId(USER)).thenReturn(List.of(view("zone:write"), view("session:read")));

        assertThat(directory.permissionsOf(USER.toString())).containsExactly("session:read", "zone:write");
    }

    @Test
    void onlyResourceActionIsEverGrantedLikeForTheClaim() {
        when(repository.findByUserId(USER)).thenReturn(List.of(view("zone:write"), view("ROLE_ADMIN"), view("*"), view("Zone:Write")));

        assertThat(directory.permissionsOf(USER.toString())).containsExactly("zone:write");
    }

    @Test
    void anIdThatIsNoUserIdOrNothingHasNoPermissionsAndIsNotQueried() {
        assertThat(directory.permissionsOf("not-a-uuid")).isEmpty();
        assertThat(directory.permissionsOf(null)).isEmpty();

        verify(repository, never()).findByUserId(any());
    }

    @Test
    void aUserWithoutARoleHasNoPermissions() {
        when(repository.findByUserId(USER)).thenReturn(List.of());

        assertThat(directory.permissionsOf(USER.toString())).isEmpty();
    }

    @Test
    void anEntryIsServedFromTheCacheUntilItsTtlIsOverAndAChangeOfRoleShowsThen() {
        when(repository.findByUserId(USER)).thenReturn(List.of(view("zone:write")));

        directory.permissionsOf(USER.toString());
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(directory.permissionsOf(USER.toString())).containsExactly("zone:write");
        verify(repository, times(1)).findByUserId(USER);

        when(repository.findByUserId(USER)).thenReturn(List.of(view("session:read")));
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(directory.permissionsOf(USER.toString())).containsExactly("session:read");
        verify(repository, times(2)).findByUserId(USER);
    }

    @Test
    void aDatabaseThatDoesNotAnswerIsAnOutageAndNeverNoPermissions() {
        when(repository.findByUserId(USER)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> directory.permissionsOf(USER.toString()))
                .isInstanceOf(PermissionsUnavailableException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }
}
