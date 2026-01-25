package com.obsinity.service.core.state.transition.counter;

import com.obsinity.service.core.config.ConfigLookup;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ConfigTerminalStateResolver implements TerminalStateResolver {
    private final ConfigLookup configLookup;

    public ConfigTerminalStateResolver(ConfigLookup configLookup) {
        this.configLookup = configLookup;
    }

    @Override
    public Set<String> terminalStates(UUID serviceId, String objectType) {
        return configLookup.terminalStates(serviceId, objectType);
    }
}
