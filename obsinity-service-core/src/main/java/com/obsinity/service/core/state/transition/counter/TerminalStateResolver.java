package com.obsinity.service.core.state.transition.counter;

import java.util.Set;
import java.util.UUID;

public interface TerminalStateResolver {
    Set<String> terminalStates(UUID serviceId, String objectType);
}
