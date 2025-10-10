package com.obsinity.flow.model;

/**
 * Indicates the origin of a telemetry holder in relation to flows and steps.
 *
 * <ul>
 *   <li>{@link #FLOW} — a regular {@code @Flow} method opened a flow.
 *   <li>{@link #FLOW_STEP} — a {@code @Step} executed inside an active flow (nested step); a temporary step-holder will
 *       be folded into the parent as an event.
 *   <li>{@link #STEP_FLOW} — a {@code @Step} ran with no active flow and was promoted to a flow.
 * </ul>
 */
public enum Origin {
    FLOW,
    FLOW_STEP,
    STEP_FLOW
}
