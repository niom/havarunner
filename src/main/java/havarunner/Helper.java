package havarunner;

import havarunner.scenario.FrameworkMethodAndScenario;
import havarunner.scenario.ScenarioHelper;
import havarunner.scenario.TestWithMultipleScenarios;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.TestClass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static havarunner.Ensure.ensuringPackagePrivate;
import static havarunner.scenario.ScenarioHelper.*;

class Helper {

    static List<FrameworkMethodAndScenario> toFrameworkMethods(TestClass testClass) {
        List<FrameworkMethodAndScenario> frameworkMethods = new ArrayList<>();
        for (MethodAndScenario methodAndScenario : findTestMethods(testClass)) {
            frameworkMethods.add(
                new FrameworkMethodAndScenario(
                    Ensure.ensuringSnakeCased(
                        ensuringPackagePrivate(
                            new FrameworkMethod(methodAndScenario.method)
                        )
                    ),
                    methodAndScenario.scenario
                )
            );
        }
        return frameworkMethods;
    }

    static Object newTestClassInstance(TestClass testClass) {
        try {
            return findOnlyConstructor(testClass).newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Constructor findOnlyConstructor(TestClass testClass) {
        Constructor<?>[] declaredConstructors = testClass.getJavaClass().getDeclaredConstructors();
        Assert.assertEquals(
            String.format("The class %s should have exactly one no-arg constructor", testClass.getJavaClass().getName()),
            1,
            declaredConstructors.length
        );
        return declaredConstructors[0];
    }

    private static List<MethodAndScenario> findTestMethods(TestClass testClass) {
        List<MethodAndScenario> testMethods = new ArrayList<>();
        for (Object scenario : scenarios(testClass)) {
            for (Method method : testClass.getJavaClass().getDeclaredMethods()) {
                if (method.getAnnotation(Test.class) != null) {
                    method.setAccessible(true);
                    testMethods.add(new MethodAndScenario(scenario, method));
                }
            }
        }

        return testMethods;
    }

    private static Set scenarios(TestClass testClass) {
        if (isScenarioClass(testClass.getJavaClass())) {
            return ((TestWithMultipleScenarios) newTestClassInstance(testClass)).scenarios();
        } else {
            return Collections.singleton(defaultScenario);
        }
    }

    static boolean isScenarioClass(Class clazz) {
        return TestWithMultipleScenarios.class.isAssignableFrom(clazz);
    }

    private static class MethodAndScenario {
        private final Object scenario;
        private final Method method;

        public MethodAndScenario(Object scenario, Method method) {
            this.scenario = scenario;
            this.method = method;
        }
    }
}
