package com.github.havarunner.suite.suitedefinedinparent;

import com.github.havarunner.HavaRunner;
import org.junit.After;
import org.junit.Test;

import static com.github.havarunner.TestHelper.run;
import static org.junit.Assert.assertTrue;

public class SuiteDefinedInParentTest {

    static boolean suiteMethodCalled;
    static boolean innerSuiteMethodCalled;

    @After
    public void reset() {
        suiteMethodCalled = false;
        innerSuiteMethodCalled = false;
    }

    @Test
    public void abstract_classes_may_declare_suite_membership() {
        run(new HavaRunner(SuiteMember.class));
        assertTrue(suiteMethodCalled);
    }

    @Test
    public void abstract_classes_may_declare_suite_membership_for_static_inner_classes() {
        run(new HavaRunner(SuiteMember.class));
        assertTrue(innerSuiteMethodCalled);
    }

    @Test
    public void the_suite_should_find_its_members_even_though_the_membership_is_declared_in_an_abstract_class_and_the_concrete_class_is_a_static_inner_class() {
        run(new HavaRunner(Suite.class));
        assertTrue(innerSuiteMethodCalled);
    }

    @Test
    public void the_suite_should_find_its_members_even_though_the_membership_is_declared_in_an_abstract_class() {
        run(new HavaRunner(Suite.class));
        assertTrue(suiteMethodCalled);
    }
}
