package com.obsinity.client.core;

import java.util.HashMap;
import java.util.Map;

/** Thread-local storage for attributes and context (very small demo). */
public final class TelemetryContext {
  private static final ThreadLocal<Map<String,Object>> ATTRS = ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<Map<String,Object>> CTX   = ThreadLocal.withInitial(HashMap::new);

  private TelemetryContext() {}

  public static void putAttr(String k, Object v) { ATTRS.get().put(k, v); }
  public static void putContext(String k, Object v) { CTX.get().put(k, v); }

  public static Map<String,Object> snapshotAttrs() { return new HashMap<>(ATTRS.get()); }
  public static Map<String,Object> snapshotContext() { return new HashMap<>(CTX.get()); }

  public static void clear() { ATTRS.remove(); CTX.remove(); }
}
