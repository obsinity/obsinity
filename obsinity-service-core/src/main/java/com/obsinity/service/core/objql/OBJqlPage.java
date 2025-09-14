package com.obsinity.service.core.objql;

/**
 * Paging options for OB-JQL execution.
 * <p>
 * - {@code offset} is 0-based.
 * - {@code limit} is clamped to [1, MAX_LIMIT].
 */
public record OBJqlPage(long offset, int limit) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    /**
     * Creates a page with sane defaults and clamping.
     * Nulls allowed (use defaults).
     */
    public static OBJqlPage of(Long offset, Integer limit) {
        long off = (offset == null || offset < 0) ? 0 : offset;
        int lim = (limit == null) ? DEFAULT_LIMIT : limit;
        if (lim <= 0) lim = DEFAULT_LIMIT;
        if (lim > MAX_LIMIT) lim = MAX_LIMIT;
        return new OBJqlPage(off, lim);
    }

    /** First page: offset=0, limit=DEFAULT_LIMIT. */
    public static OBJqlPage firstPage() {
        return new OBJqlPage(0, DEFAULT_LIMIT);
    }

    /** Returns a new page advanced by one window from this page. */
    public OBJqlPage next() {
        long nextOffset = Math.max(0, this.offset + (long) this.limit);
        return new OBJqlPage(nextOffset, this.limit);
    }

    /** Returns a new page moved back by one window from this page. */
    public OBJqlPage prev() {
        long prevOffset = Math.max(0, this.offset - (long) this.limit);
        return new OBJqlPage(prevOffset, this.limit);
    }
}
