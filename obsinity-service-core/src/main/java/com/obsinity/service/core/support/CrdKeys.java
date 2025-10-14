package com.obsinity.service.core.support;

public final class CrdKeys {
    private CrdKeys() {}

    public static final String KEY_API_VERSION = "apiVersion";
    public static final String KEY_KIND = "kind";

    public static final String API_VERSION_LOWER = "obsinity/v1";

    public static final String KIND_EVENT = "event";
    public static final String KIND_METRIC_COUNTER = "metriccounter";
    public static final String KIND_METRIC_HISTOGRAM = "metrichistogram";

    public static final String METADATA = "metadata";
    public static final String SPEC = "spec";
    public static final String SCHEMA = "schema";
    public static final String PROPERTIES = "properties";
    public static final String INDEX = "index";
    public static final String NAME = "name";
    public static final String SERVICE = "service";
    public static final String LABELS = "labels";
    public static final String CATEGORY = "category";
    public static final String SUB_CATEGORY = "subCategory";

    public static final String KEY = "key";
    public static final String DIMENSIONS = "dimensions";
    public static final String ROLLUP = "rollup";
    public static final String WINDOWING = "windowing";
    public static final String GRANULARITIES = "granularities";
    public static final String VALUE = "value";
    public static final String BUCKETS = "buckets";
    public static final String FOLD = "fold";
    public static final String ATTRIBUTE_MAPPING = "attributeMapping";
    public static final String FILTERS = "filters";
    public static final String STATE = "state";
    public static final String CUTOVER_AT = "cutover_at";
    public static final String GRACE_UNTIL = "grace_until";

    public static final String RETENTION = "retention";
    public static final String TTL = "ttl";

    public static final String TYPE = "type";
    public static final String TYPE_OBJECT = "object";
    public static final String TYPE_ARRAY = "array";
    public static final String ITEMS = "items";
}
