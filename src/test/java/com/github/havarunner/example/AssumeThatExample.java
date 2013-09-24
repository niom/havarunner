package com.github.havarunner.example;

import com.github.havarunner.HavaRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(HavaRunner.class)
public class AssumeThatExample {
    boolean weHaveFlt = false;

    @Test
    void when_we_fare_the_galaxies() {
        org.junit.Assume.assumeTrue(weHaveFlt); // HavaRunner ignores this test, because the assumption does not hold
    }
}
