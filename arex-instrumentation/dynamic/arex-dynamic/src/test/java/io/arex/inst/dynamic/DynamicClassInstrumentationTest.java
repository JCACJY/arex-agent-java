package io.arex.inst.dynamic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.arex.agent.bootstrap.model.MockResult;
import io.arex.agent.bootstrap.util.StringUtil;
import io.arex.inst.dynamic.common.DynamicClassExtractor;
import io.arex.inst.extension.MethodInstrumentation;
import io.arex.inst.runtime.context.ArexContext;
import io.arex.inst.runtime.context.ContextManager;
import io.arex.inst.runtime.context.RepeatedCollectManager;
import io.arex.inst.runtime.model.ArexConstants;
import io.arex.inst.runtime.model.DynamicClassEntity;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodDescription.ForLoadedMethod;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.ForLoadedType;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DynamicClassInstrumentationTest {
    static DynamicClassInstrumentation target = null;
    static List<DynamicClassEntity> dynamicClassList = Collections.singletonList(
            new DynamicClassEntity("", "", "", null));

    DynamicClassInstrumentationTest() {
    }

    @BeforeAll
    static void setUp() {
        target = new DynamicClassInstrumentation(dynamicClassList);
        Mockito.mockStatic(ContextManager.class);
        Mockito.mockStatic(RepeatedCollectManager.class);
    }

    @AfterAll
    static void tearDown() {
        target = null;
        Mockito.clearAllCaches();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        "DynamicTestClass",
        "io.arex.inst.dynamic.*namicTest*",
        "io.arex.inst.dynamic.*namicTestClass",
        "io.arex.inst.dynamic.DynamicTest*",
        "io.arex.inst.dynamic.DynamicTestClass",
        "ac:io.arex.inst.dynamic.AbstractDynamicTestClass"
    })
    void typeMatcher(String fullClazzName) {
        List<DynamicClassEntity> dynamicClassList = new ArrayList<>();
        dynamicClassList.add(new DynamicClassEntity(fullClazzName, "", "", null));
        DynamicClassInstrumentation instrumentation = new DynamicClassInstrumentation(dynamicClassList);
        ElementMatcher<TypeDescription> matcher = instrumentation.matcher();
        if (fullClazzName.equals("DynamicTestClass")) {
            assertFalse(matcher.matches(ForLoadedType.of(DynamicTestClass.class)));
            return;
        }
        assertTrue(matcher.matches(ForLoadedType.of(DynamicTestClass.class)));
    }

    @Test
    void adviceClassNames() {
        assertEquals(6, target.adviceClassNames().size());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("methodAdvicesCase")
    void testMethodAdvices(String testName, List<DynamicClassEntity> dynamicClassList, Predicate<List<MethodInstrumentation>> predicate) {
        DynamicClassInstrumentation instrumentation = new DynamicClassInstrumentation(dynamicClassList);
        List<MethodInstrumentation> methodAdvices = instrumentation.methodAdvices();
        assertTrue(predicate.test(methodAdvices));
    }

    static Predicate<List<MethodInstrumentation>> NOT_EMPTY_PREDICATE = result -> !result.isEmpty();

    static int matchedMethodCount(ElementMatcher<? super MethodDescription> matcher, Class<?> matchedClazz) {
        Method[] methods = matchedClazz.getDeclaredMethods();
        List<String> matchedMethods = new ArrayList<>();
        List<String> nonMatchedMethods = new ArrayList<>();
        for (Method method : methods) {
            if (matcher.matches(new ForLoadedMethod(method))) {
                matchedMethods.add(method.getName());
            } else {
                nonMatchedMethods.add(method.getName());
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("matcher: ").append(matcher.toString()).append("\n")
                .append("matched Class: ").append(matchedClazz.getName()).append("\n")
                .append("matched ").append(matchedMethods.size()).append(" methods: ")
                .append(StringUtil.join(matchedMethods, ", ")).append("\n")
                .append("nonMatched ").append(nonMatchedMethods.size()).append(" methods: ")
                .append(StringUtil.join(nonMatchedMethods, ", ")).append("\n");
        System.out.println(builder);

        return matchedMethods.size();
    }

    static Stream<Arguments> methodAdvicesCase() {
        DynamicClassEntity emptyOperation = new DynamicClassEntity("io.arex.inst.dynamic.DynamicTestClass", "", "", "");
        Predicate<List<MethodInstrumentation>> emptyOperationPredicate = methodAdvices -> {
            ElementMatcher<? super MethodDescription> matcher = methodAdvices.get(0).getMethodMatcher();
            return methodAdvices.size() == 1 && matchedMethodCount(matcher, DynamicTestClass.class) == 2;
        };

        DynamicClassEntity testReturnVoidEntity = new DynamicClassEntity("io.arex.inst.dynamic.DynamicTestClass", "testReturnVoid", "", "");
        DynamicClassEntity testReturnVoidWithParameterEntity = new DynamicClassEntity("io.arex.inst.dynamic.DynamicTestClass", "testReturnVoidWithParameter", "java.lang.String", "java.lang.System.currentTimeMillis");
        Predicate<List<MethodInstrumentation>> emptyOperationAndVoidPredicate = methodAdvices -> {
            ElementMatcher<? super MethodDescription> matcher = methodAdvices.get(0).getMethodMatcher();
            return methodAdvices.size() == 1 && matchedMethodCount(matcher, DynamicTestClass.class) == 0;
        };

        DynamicClassEntity testReturnNonPrimitiveTypeWithParameterEntity = new DynamicClassEntity("io.arex.inst.dynamic.DynamicTestClass", "testReturnNonPrimitiveTypeWithParameter", "java.lang.String", null);
        DynamicClassEntity testReturnPrimitiveTypeWithParameter = new DynamicClassEntity("io.arex.inst.dynamic.DynamicTestClass", "testReturnPrimitiveTypeWithParameter", "int", null);
        Predicate<List<MethodInstrumentation>> operationWithParameterPredicate = methodAdvices -> {
            ElementMatcher<? super MethodDescription> matcher = methodAdvices.get(0).getMethodMatcher();
            return methodAdvices.size() == 1 && matchedMethodCount(matcher, DynamicTestClass.class) == 2;
        };

        DynamicClassEntity testReturnWithParameterWildcard = new DynamicClassEntity("io.arex.inst.dynamic.DynamicTestClass", "*WithParameter*,testReturnVoid*,*WithParameter", "", null);

        return Stream.of(
                arguments("should_match_2_methods_when_empty_operation", Collections.singletonList(emptyOperation), NOT_EMPTY_PREDICATE.and(emptyOperationPredicate)),
                arguments("should_match_0_method_when_with_return_void", Arrays.asList(testReturnVoidEntity, testReturnVoidWithParameterEntity), NOT_EMPTY_PREDICATE.and(emptyOperationAndVoidPredicate)),
                arguments("should_match_2_method_when_with_parameter", Arrays.asList(testReturnNonPrimitiveTypeWithParameterEntity, testReturnPrimitiveTypeWithParameter), NOT_EMPTY_PREDICATE.and(operationWithParameterPredicate)),
                arguments("should_match_2_method_when_with_parameter_wildcard", Arrays.asList(testReturnWithParameterWildcard), NOT_EMPTY_PREDICATE.and(operationWithParameterPredicate))
        );
    }

    @Test
    void onEnter() {
        AtomicReference<DynamicClassExtractor> atomicReference = new AtomicReference<>();
        Mockito.when(ContextManager.needRecord()).thenReturn(false);
        Mockito.when(ContextManager.needReplay()).thenReturn(true);
//        Mockito.when(ContextManager.needRecord()).thenReturn(false);

        try (MockedConstruction<DynamicClassExtractor> mocked = Mockito.mockConstruction(DynamicClassExtractor.class, (mock, context) -> {
            System.out.println("mock DynamicClassExtractor");
            atomicReference.set(mock);
            Mockito.when(mock.replay()).thenReturn(MockResult.success(false, null));
        })) {
            assertTrue(DynamicClassInstrumentation.MethodAdvice.onEnter( null, null, null, null,null, new DynamicClassExtractor(null, null, null, null)));
        }


        Mockito.when(ContextManager.needRecord()).thenReturn(false);
        Mockito.when(ContextManager.needReplay()).thenReturn(false);
        assertFalse(DynamicClassInstrumentation.MethodAdvice.onEnter(null, null, null, null, null, atomicReference.get()));

        Mockito.when(ContextManager.needRecord()).thenReturn(true);
        assertFalse(DynamicClassInstrumentation.MethodAdvice.onEnter(null, null, null, null, null, null));
    }

    @ParameterizedTest
    @MethodSource("onExitCase")
    void onExit(Runnable mocker, MockResult mockResult, Predicate<MockResult> predicate) {
        mocker.run();
        AtomicReference<DynamicClassExtractor> atomicReference = new AtomicReference<>();
        try (MockedConstruction<DynamicClassExtractor> mocked = Mockito.mockConstruction(DynamicClassExtractor.class, (mock, context) -> {
            System.out.println("mock DynamicClassExtractor");
            atomicReference.set(mock);
            Mockito.doNothing().when(mock).record();
        })) {
            DynamicClassInstrumentation.MethodAdvice.onExit(
                "java.lang.System", "getenv", new Object[]{"java.lang.String"}, mockResult, new DynamicClassExtractor(null, null, null, null), null, null);
            assertTrue(predicate.test(mockResult));
        }
    }
    @Test
    void onExitSetResponse() {
        Mockito.when(ContextManager.needRecord()).thenReturn(true);
        Mockito.when(RepeatedCollectManager.exitAndValidate()).thenReturn(true);
        AtomicReference<DynamicClassExtractor> atomicReference = new AtomicReference<>();
        Object mockResult = "nomalResult";
        Future futureResult = Executors.newFixedThreadPool(2).submit(() -> System.out.println("yes"));
        try (MockedConstruction<DynamicClassExtractor> mocked = Mockito.mockConstruction(DynamicClassExtractor.class, (mock, context) -> {
            System.out.println("mock DynamicClassExtractor");
            atomicReference.set(mock);
            Mockito.doNothing().when(mock).record();
        })) {
            DynamicClassInstrumentation.MethodAdvice.onExit(
                    "java.lang.System", "getenv", new Object[]{"java.lang.String"}, null, new DynamicClassExtractor(null, null, null, null), null, mockResult);
            Mockito.verify(atomicReference.get()).setResponse(mockResult);

            DynamicClassInstrumentation.MethodAdvice.onExit(
                    "java.lang.System", "getenv", new Object[]{"java.lang.String"}, null, new DynamicClassExtractor(null, null, null, null), null, futureResult);
            Mockito.verify(atomicReference.get()).setFutureResponse(futureResult);
        }
    }

    static Stream<Arguments> onExitCase() {
        Runnable mockerNeedReplay = () -> {
            Mockito.when(ContextManager.currentContext()).thenReturn(ArexContext.of("test-case-id"));
        };
        Runnable mockerNeedRecord = () -> {
            Mockito.when(ContextManager.needRecord()).thenReturn(true);
            Mockito.when(RepeatedCollectManager.exitAndValidate()).thenReturn(true);
        };
        Predicate<MockResult> predicate1 = Objects::isNull;
        Predicate<MockResult> predicate2 = Objects::nonNull;
        return Stream.of(
                arguments(mockerNeedReplay, null, predicate1),
                arguments(mockerNeedRecord, MockResult.success("mock"), predicate2),
                arguments(mockerNeedRecord, MockResult.success(new RuntimeException()), predicate2),
                arguments(mockerNeedRecord, null, predicate1)
        );
    }

    @Test
    void testTransformer() {
        DynamicClassEntity testSystem = new DynamicClassEntity("io.arex.inst.dynamic.ReplaceMethodClass", "currentTimeMillis", "", ArexConstants.CURRENT_TIME_MILLIS_SIGNATURE);
        DynamicClassEntity testUUID = new DynamicClassEntity("io.arex.inst.dynamic.ReplaceMethodClass", "uuid", "", ArexConstants.UUID_SIGNATURE);
        DynamicClassEntity testNextInt = new DynamicClassEntity("io.arex.inst.dynamic.ReplaceMethodClass", "nextInt", "", ArexConstants.NEXT_INT_SIGNATURE);
        DynamicClassEntity testAll = new DynamicClassEntity("io.arex.inst.dynamic.ReplaceMethodClass", "", "", ArexConstants.NEXT_INT_SIGNATURE);
        DynamicClassEntity testNoMethod = new DynamicClassEntity("io.arex.inst.dynamic.ReplaceMethodClass", "next", "", ArexConstants.NEXT_INT_SIGNATURE);
        try (MockedStatic<ReplaceMethodHelper> replaceTimeMillsMockMockedStatic = Mockito.mockStatic(
                ReplaceMethodHelper.class)) {
            replaceTimeMillsMockMockedStatic.when(ReplaceMethodHelper::currentTimeMillis).thenReturn(3L);
            replaceTimeMillsMockMockedStatic.when(ReplaceMethodHelper::uuid).thenReturn(UUID.fromString("7eb4f958-671a-11ed-9022-0242ac120002"));
            DynamicClassInstrumentation target = new DynamicClassInstrumentation(Arrays.asList(testSystem, testUUID, testNextInt, testAll, testNoMethod));
            replaceTimeMillsMockMockedStatic.when(() -> ReplaceMethodHelper.nextInt(new Random(), 10)).thenReturn(2);

            ResettableClassFileTransformer resettableClassFileTransformer =
                    new AgentBuilder.Default(new ByteBuddy())
                            .type(target.matcher())
                            .transform(target.transformer())
                            .installOnByteBuddyAgent();

            ReplaceMethodClass testClass = new ReplaceMethodClass();
            assertDoesNotThrow(testClass::currentTimeMillis);
            assertDoesNotThrow(testClass::uuid);
            assertDoesNotThrow(testClass::nextInt);
        }
    }
}