package com.homeease.backend.integration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DisposableDatabaseGuardTest {

    @Test
    void accepts_a_disposable_local_database() {
        assertThatCode(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://localhost:5432/homeease_it_test")).doesNotThrowAnyException();
        assertThatCode(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://127.0.0.1:55432/homeease_disposable")).doesNotThrowAnyException();
        assertThatCode(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://[::1]:5432/kafka_test?sslmode=disable")).doesNotThrowAnyException();
    }

    @Test
    void refuses_managed_or_remote_hosts_including_the_old_committed_default() {
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe(
                "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("managed/production");
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://db.internal.example:5432/homeease_test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LOCAL");
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://10.0.0.5:5432/homeease_test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refuses_databases_that_do_not_look_disposable() {
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://localhost:5432/postgres"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("database name");
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://localhost:5432/homeease"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe("jdbc:postgresql://localhost:5432/"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refuses_malformed_input_and_never_echoes_a_password_from_the_query_string() {
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe("mysql://localhost/test")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DisposableDatabaseGuard.requireSafe(
                "jdbc:postgresql://remote.example/prod?user=u&password=Sup3rSecret"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("Sup3rSecret");
    }
}
