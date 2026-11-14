package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.homeease.backend.service.WorkerPresenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression test for a startup crash that no other test could see.
 *
 * <p>The real application context holds TWO {@code RedisTemplate<String,String>} candidates: this project's own
 * {@code redisTemplate} ({@link RedisConfig}) and Spring Boot's auto-configured {@code stringRedisTemplate} (a
 * {@code StringRedisTemplate} IS-A {@code RedisTemplate<String,String>}). Without a primary, Spring breaks that tie only
 * by matching the injection point's NAME to a bean name. {@link WorkerPresenceService} names its parameter {@code redis},
 * so the core service failed to start with "expected single matching bean but found 2: redisTemplate,stringRedisTemplate"
 * (seen on CI in BookingKafkaIntegrationTest, the only test that boots the whole context). The wiring tests that register
 * a single mock {@code RedisTemplate} cannot reproduce that, so these tests use the real {@code RedisConfig} plus the
 * real Boot auto-configuration, over a mocked connection factory (no network, no Redis).
 */
class RedisTemplateWiringTest {

    private static ApplicationContextRunner productionRedisBeans() {
        return new ApplicationContextRunner()
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withUserConfiguration(RedisConfig.class)
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class));
    }

    /** Consumer whose parameter name matches NO bean name: it can only resolve through a primary bean. */
    static class AnyNameConsumer {
        final RedisTemplate<String, String> injected;

        AnyNameConsumer(RedisTemplate<String, String> aNameThatMatchesNoBean) {
            this.injected = aNameThatMatchesNoBean;
        }
    }

    /** Consumer of the Boot-provided type, as RedisService is. */
    static class StringTemplateConsumer {
        final StringRedisTemplate injected;

        StringTemplateConsumer(StringRedisTemplate redisTemplate) {
            this.injected = redisTemplate;
        }
    }

    @Test
    void premise_production_wiring_really_has_two_RedisTemplate_String_String_candidates() {
        // If Spring Boot ever stops creating the second one, the tests below would pass vacuously; fail loudly instead.
        productionRedisBeans().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanNamesForType(
                    ResolvableType.forClassWithGenerics(RedisTemplate.class, String.class, String.class)))
                    .containsExactlyInAnyOrder("redisTemplate", "stringRedisTemplate");
        });
    }

    @Test
    void WorkerPresenceService_wires_against_the_real_redis_beans_and_gets_the_application_template() {
        productionRedisBeans().withBean(WorkerPresenceService.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(ctx.getBean(WorkerPresenceService.class), "redis"))
                    .isSameAs(ctx.getBean("redisTemplate"));
        });
    }

    @Test
    void a_RedisTemplate_parameter_resolves_to_the_application_template_whatever_it_is_called() {
        productionRedisBeans().withBean(AnyNameConsumer.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AnyNameConsumer.class).injected).isSameAs(ctx.getBean("redisTemplate"));
        });
    }

    @Test
    void StringRedisTemplate_consumers_still_get_boots_stringRedisTemplate() {
        productionRedisBeans().withBean(StringTemplateConsumer.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(StringTemplateConsumer.class).injected).isSameAs(ctx.getBean("stringRedisTemplate"));
        });
    }
}
