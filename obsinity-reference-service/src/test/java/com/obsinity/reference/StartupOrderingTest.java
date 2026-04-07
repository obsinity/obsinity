package com.obsinity.reference;

import static org.assertj.core.api.Assertions.assertThat;

import com.obsinity.reference.api.SampleDataController;
import com.obsinity.service.core.config.init.ConfigInitCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

class StartupOrderingTest {

    @Test
    void runsConfigInitBeforeDemoGeneratorsOnApplicationReady() throws Exception {
        Order configOrder = ConfigInitCoordinator.class.getMethod("onStartup").getAnnotation(Order.class);
        Order sampleOrder = SampleDataController.class
                .getMethod("startUnifiedDemoOnStartup")
                .getAnnotation(Order.class);
        Order profileOrder = Class.forName("com.obsinity.reference.demodata.DbDrivenProfileGenerator")
                .getMethod("startupRun")
                .getAnnotation(Order.class);

        assertThat(configOrder).isNotNull();
        assertThat(sampleOrder).isNotNull();
        assertThat(profileOrder).isNotNull();
        assertThat(configOrder.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(sampleOrder.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(profileOrder.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
