package com.obsinity.service.core.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class ServicePartitionKey {

    public static final int HEX_LENGTH = 8;

    private ServicePartitionKey() {}

    public static String forServiceKey(String input) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256")
                    .digest(input.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HEX_LENGTH);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", sha[i]));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute service partition key hash", ex);
        }
    }
}
