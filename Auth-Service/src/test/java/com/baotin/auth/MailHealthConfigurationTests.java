package com.baotin.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailHealthContributorAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class MailHealthConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class,
            MailHealthContributorAutoConfiguration.class));

    @Test void disabledByDefault() {
        context.run(c -> assertThat(c).doesNotHaveBean("mailHealthContributor"));
    }

    @Test void enabledWithSmtpEnvironmentSetting() {
        context.withPropertyValues("SMTP_ENABLED=true")
            .run(c -> assertThat(c).hasBean("mailHealthContributor"));
    }

    @Test void enabledWithConfigTreeProperty() {
        context.withPropertyValues("auth.mail.enabled=true")
            .run(c -> assertThat(c).hasBean("mailHealthContributor"));
    }
}
