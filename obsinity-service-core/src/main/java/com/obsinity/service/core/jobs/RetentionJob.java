package com.obsinity.service.core.jobs;

/** Placeholder scheduled job for data retention. */
public class RetentionJob implements Runnable {
    @Override
    public void run() {
        // TODO: drop aged partitions
    }
}
