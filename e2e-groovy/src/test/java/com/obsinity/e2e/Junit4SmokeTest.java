package com.obsinity.e2e;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class Junit4SmokeTest {
    @Rule
    public TestName testName = new TestName();

    @Before
    public void logTestStart() {
        System.out.println("TEST START: " + testName.getMethodName());
    }

    @Test
    public void junit4DiscoveryWorks() {
        assertTrue(true);
    }
}
