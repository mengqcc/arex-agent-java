package io.arex.inst.dynamic.common;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.arex.agent.bootstrap.model.MockResult;
import io.arex.agent.bootstrap.model.MockStrategyEnum;
import io.arex.agent.bootstrap.model.Mocker;
import io.arex.agent.bootstrap.util.ArrayUtils;
import io.arex.agent.bootstrap.util.StringUtil;
import io.arex.agent.thirdparty.util.time.DateFormatUtils;
import io.arex.inst.dynamic.common.listener.ListenableFutureAdapter;
import io.arex.inst.dynamic.common.listener.MonoConsumer;
import io.arex.inst.dynamic.common.listener.ResponseConsumer;
import io.arex.inst.runtime.config.Config;
import io.arex.inst.runtime.context.ArexContext;
import io.arex.inst.runtime.context.ContextManager;
import io.arex.inst.runtime.model.DynamicClassEntity;
import io.arex.inst.runtime.serializer.Serializer;
import io.arex.inst.runtime.util.IgnoreUtils;
import io.arex.inst.runtime.log.LogManager;
import io.arex.inst.runtime.util.MockUtils;
import io.arex.inst.runtime.util.TypeUtil;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import reactor.core.publisher.Mono;

public class DynamicClassExtractor {
    private static final int RESULT_SIZE_MAX = Integer.parseInt(System.getProperty("arex.dynamic.result.size.limit", "1000"));
    private static final String SERIALIZER = "gson";
    private static final String LISTENABLE_FUTURE = "com.google.common.util.concurrent.ListenableFuture";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final String NEED_RECORD_TITLE = "dynamic.needRecord";
    private static final String NEED_REPLAY_TITLE = "dynamic.needReplay";
    public static final String MONO = "reactor.core.publisher.Mono";
    private static final String JODA_LOCAL_DATE_TIME = "org.joda.time.LocalDateTime";
    private static final String JODA_LOCAL_TIME = "org.joda.time.LocalTime";
    public static final String SIMPLE_DATE_FORMAT_MILLIS = "yyyy-MM-dd HH:mm:";
    private static final String SIMPLE_DATE_FORMAT_MILLIS_WITH_ZONE = "yyyy-MM-dd'T'HH:mm:";
    public static final String SHORT_TIME_FORMAT_MILLISECOND = "HH:mm:";
    private static final String TIME_ZONE = "ZZZ";
    private static final String ZERO_SECOND_TIME = "00.000";
    private final String clazzName;
    private final String methodName;
    private final String methodKey;
    private String serializedResult;
    private Object result;
    private String resultClazz;
    private String methodSignatureKey;
    private final String methodReturnType;
    private int methodSignatureKeyHash;
    private final Class<?> actualType;
    private final Object[] args;
    private final String dynamicSignature;

    public DynamicClassExtractor(Method method, Object[] args, String keyExpression, Class<?> actualType) {
        this.clazzName = method.getDeclaringClass().getName();
        this.methodName = method.getName();
        this.args = args;
        this.dynamicSignature = getDynamicEntitySignature();
        this.methodKey = buildMethodKey(method, args, keyExpression);
        this.methodReturnType = TypeUtil.getName(method.getReturnType());
        this.actualType = actualType;
    }

    public DynamicClassExtractor(Method method, Object[] args) {
        this.clazzName = method.getDeclaringClass().getName();
        this.methodName = method.getName();
        this.args = args;
        this.dynamicSignature = getDynamicEntitySignature();
        this.methodKey = buildMethodKey(method, args);
        this.methodReturnType = TypeUtil.getName(method.getReturnType());
        this.actualType = null;
    }

    public Object recordResponse(Object response) {
        if (IgnoreUtils.invalidOperation(dynamicSignature)) {
            LogManager.warn(NEED_RECORD_TITLE,
                    StringUtil.format("do not record invalid operation: %s, can not serialize args or response", dynamicSignature));
            return response;
        }
        if (response instanceof Future<?>) {
            this.setFutureResponse((Future<?>) response);
            return response;
        }
        // Compatible with not import package reactor-core
        if (MONO.equals(methodReturnType) && response instanceof Mono<?>) {
            return new MonoConsumer(this).accept((Mono<?>) response);
        }
        this.result = response;
        if (needRecord()) {
            this.resultClazz = buildResultClazz(TypeUtil.getName(response));
            Mocker mocker = makeMocker();
            this.serializedResult = serialize(this.result);
            mocker.getTargetResponse().setBody(this.serializedResult);
            MockUtils.recordMocker(mocker);
            cacheMethodSignature();
        }
        return response;
    }

    public MockResult replay() {
        if (IgnoreUtils.invalidOperation(dynamicSignature)) {
            LogManager.warn(NEED_REPLAY_TITLE,
                    StringUtil.format("do not replay invalid operation: %s, can not serialize args or response", dynamicSignature));
            return MockResult.IGNORE_MOCK_RESULT;
        }
        String key = buildCacheKey();
        Map<String, Object> cachedReplayResultMap = ContextManager.currentContext()
                .getCachedReplayResultMap();
        Object replayResult = null;
        // First get replay result from cache
        if (key != null) {
            replayResult = cachedReplayResultMap.get(key);
        }

        // If not in cache, get replay result from mock server
        if (replayResult == null) {
            Mocker replayMocker = MockUtils.replayMocker(makeMocker(), MockStrategyEnum.FIND_LAST);
            if (MockUtils.checkResponseMocker(replayMocker)) {
                String typeName = replayMocker.getTargetResponse().getType();
                replayResult = deserializeResult(replayMocker, typeName);
            }
            replayResult = restoreResponse(replayResult);
            // no key no cache, no parameter methods may return different values
            if (key != null && replayResult != null) {
                cachedReplayResultMap.put(key, replayResult);
            }
        }
        boolean ignoreMockResult = IgnoreUtils.ignoreMockResult(clazzName, methodName);
        return MockResult.success(ignoreMockResult, replayResult);
    }

    private Object deserializeResult(Mocker replayMocker, String typeName) {
        return Serializer.deserialize(replayMocker.getTargetResponse().getBody(), typeName, SERIALIZER);
    }

    void setFutureResponse(Future<?> result) {
        if (result instanceof CompletableFuture) {
            ((CompletableFuture<?>) result).whenComplete(new ResponseConsumer(this));
            return;
        }

        // Compatible with not import Guava
        if (LISTENABLE_FUTURE.equals(methodReturnType)) {
            ListenableFutureAdapter.addCallBack((ListenableFuture<?>) result, this);
        }
    }

    String buildResultClazz(String resultClazz) {
        if (StringUtil.isEmpty(resultClazz) || resultClazz.contains(TypeUtil.HORIZONTAL_LINE_STR)) {
            return resultClazz;
        }

        // @ArexMock actualType
        if (actualType != null && Object.class != actualType) {
            return resultClazz + TypeUtil.HORIZONTAL_LINE + actualType.getName();
        }

        if (Config.get() == null || Config.get().getDynamicClassSignatureMap().isEmpty()) {
            return resultClazz;
        }

        DynamicClassEntity dynamicEntity = Config.get().getDynamicEntity(dynamicSignature);

        if (dynamicEntity == null || StringUtil.isEmpty(dynamicEntity.getActualType())) {
            return resultClazz;
        }

        return resultClazz + TypeUtil.HORIZONTAL_LINE + dynamicEntity.getActualType();
    }

    String buildMethodKey(Method method, Object[] args, String keyExpression) {
        if (ArrayUtils.isEmpty(args)) {
            return null;
        }

        String key = ExpressionParseUtil.generateKey(method, args, keyExpression);
        if (key != null || StringUtil.isNotEmpty(keyExpression)) {
            return key;
        }

        return serialize(normalizeArgs(args));
    }

    /**
     * There will be a second-level difference between time type recording and playback,
     * resulting in inability to accurately match data. And in order to be compatible with previously recorded data,
     * the second time is cleared to zero.
     * ex: 2023-01-01 12:12:01.123 -> 2023-01-01 12:12:00.000
     */
    private Object[] normalizeArgs(Object[] args) {
        if (ArrayUtils.isEmpty(args)) {
            return args;
        }
        Object[] normalizedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            normalizedArgs[i] = normalizeArg(args[i]);
        }
        return normalizedArgs;
    }

    private Object normalizeArg(Object arg) {
        if (arg == null) {
            return null;
        }

        if (arg instanceof LocalDateTime) {
            return zeroTimeSecond(DateFormatUtils.format((LocalDateTime) arg, SIMPLE_DATE_FORMAT_MILLIS));
        }

        if (arg instanceof LocalTime) {
            return zeroTimeSecond(DateFormatUtils.format((LocalTime) arg, SHORT_TIME_FORMAT_MILLISECOND));
        }

        if (arg instanceof Calendar) {
            Calendar calendar = (Calendar) arg;
            String timeZone = DateFormatUtils.format(calendar, TIME_ZONE, calendar.getTimeZone());
            return zeroTimeSecond(DateFormatUtils.format(calendar, SIMPLE_DATE_FORMAT_MILLIS_WITH_ZONE, calendar.getTimeZone())) + timeZone;
        }

        if (arg instanceof Date) {
            return zeroTimeSecond(DateFormatUtils.format((Date) arg, SIMPLE_DATE_FORMAT_MILLIS));
        }

        if (JODA_LOCAL_DATE_TIME.equals(arg.getClass().getName())) {
            return zeroTimeSecond(((org.joda.time.LocalDateTime) arg).toString(SIMPLE_DATE_FORMAT_MILLIS));
        }

        if (JODA_LOCAL_TIME.equals(arg.getClass().getName())) {
            return zeroTimeSecond(((org.joda.time.LocalTime) arg).toString(SHORT_TIME_FORMAT_MILLISECOND));
        }

        return arg;
    }

    private String zeroTimeSecond(String text) {
        return text + ZERO_SECOND_TIME;
    }


    String buildMethodKey(Method method, Object[] args) {
        if (ArrayUtils.isEmpty(args)) {
            return null;
        }

        if (Config.get() == null || Config.get().getDynamicClassSignatureMap().isEmpty()) {
            return serialize(args);
        }

        DynamicClassEntity dynamicEntity = Config.get().getDynamicEntity(dynamicSignature);
        if (dynamicEntity == null || StringUtil.isEmpty(dynamicEntity.getAdditionalSignature())) {
            return serialize(normalizeArgs(args));
        }

        String keyExpression = ExpressionParseUtil.replaceToExpression(method, dynamicEntity.getAdditionalSignature());

        return buildMethodKey(method, args, keyExpression);
    }

    private String getDynamicEntitySignature() {
        if (ArrayUtils.isEmpty(this.args)) {
            return clazzName + methodName;
        }

        return clazzName + methodName + args.length;
    }

    private Mocker makeMocker() {
        Mocker mocker = MockUtils.createDynamicClass(this.clazzName, this.methodName);
        mocker.getTargetRequest().setBody(this.methodKey);
        mocker.getTargetResponse().setBody(this.serializedResult);
        mocker.getTargetResponse().setType(this.resultClazz);
        return mocker;
    }

    Object restoreResponse(Object result) {
        if (LISTENABLE_FUTURE.equals(this.methodReturnType)) {
            if (result instanceof Throwable) {
                return Futures.immediateFailedFuture((Throwable) result);
            }
            return Futures.immediateFuture(result);
        }

        if (COMPLETABLE_FUTURE.equals(this.methodReturnType)) {
            CompletableFuture<Object> completableFuture = new CompletableFuture<>();
            if (result instanceof Throwable) {
                completableFuture.completeExceptionally((Throwable) result);
            } else {
                completableFuture.complete(result);
            }

            return completableFuture;
        }

        if (MONO.equals((this.methodReturnType))) {
            if (result instanceof Throwable) {
                return Mono.error((Throwable) result);
            }
            return Mono.justOrEmpty(result);
        }

        return result;
    }

    private boolean needRecord() {
        /*
         * Judge whether the hash value of the method signature has been recorded to avoid repeated recording.
         * The nonparametric method may return different results and needs to be recorded
         * */
        ArexContext context = ContextManager.currentContext();
        if (context != null && methodKey != null) {
            this.methodSignatureKey = buildDuplicateMethodKey();
            this.methodSignatureKeyHash = StringUtil.encodeAndHash(methodSignatureKey);
            if (context.getMethodSignatureHashList().contains(methodSignatureKeyHash)) {
                if (Config.get().isEnableDebug()) {
                    LogManager.warn(NEED_RECORD_TITLE,
                            StringUtil.format("do not record method, cuz exist same method signature: %s", methodSignatureKey));
                }
                return false;
            }
        }

        if (result == null || result instanceof Throwable) {
            return true;
        }

        try {
            int size = 0;
            if (result instanceof Collection<?>) {
                size = ((Collection<?>) result).size();
            } else if (result instanceof Map<?, ?>) {
                size = ((Map<?, ?>) result).size();
            } else if (result.getClass().isArray()) {
                size = Array.getLength(result);
            }
            if (size > RESULT_SIZE_MAX) {
                String methodInfo = methodSignatureKey == null ? buildDuplicateMethodKey() : methodSignatureKey;
                LogManager.warn(NEED_RECORD_TITLE,
                        StringUtil.format("do not record method, cuz result size:%s > max limit: %s, method info: %s",
                                String.valueOf(size), String.valueOf(RESULT_SIZE_MAX), methodInfo));
                return false;
            }
        } catch (Throwable e) {
            LogManager.warn(NEED_RECORD_TITLE, e);
        }
        return true;
    }

    private String buildDuplicateMethodKey() {
        if (Objects.isNull(result)) {
            return String.format("%s_%s_%s_no_result", clazzName, methodName, methodKey);
        }
        return String.format("%s_%s_%s_has_result_%s", clazzName, methodName, methodKey, getResultKey());
    }

    private String getResultKey() {
        String resultClassName = result.getClass().getName();
        if (result instanceof Collection<?>) {
            return resultClassName + ((Collection<?>) result).size();
        }
        if (result instanceof Map<?, ?>) {
            return resultClassName + ((Map<?, ?>) result).size();
        }
        if (result.getClass().isArray()) {
            return resultClassName + Array.getLength(result);
        }
        return resultClassName;
    }

    /**
     * cache dynamic method with hashcode of signature,in order to filter out duplicate next record
     */
    private void cacheMethodSignature() {
        ArexContext context = ContextManager.currentContext();
        if (context != null && this.methodKey != null && this.methodSignatureKey != null) {
            context.getMethodSignatureHashList().add(this.methodSignatureKeyHash);
        }
    }

    private String buildCacheKey() {
        if (StringUtil.isNotEmpty(this.methodKey)) {
            return String.format("%s_%s_%s", this.clazzName, this.methodName, this.methodKey);
        }
        return null;
    }

    public String getSerializedResult() {
        return serializedResult;
    }

    private String serialize(Object object) {
        if (IgnoreUtils.invalidOperation(dynamicSignature)) {
            return null;
        }
        try {
            return Serializer.serializeWithException(object, SERIALIZER);
        } catch (Throwable ex) {
            IgnoreUtils.addInvalidOperation(dynamicSignature);
            LogManager.warn("serializeWithException", StringUtil.format("can not serialize object: %s, cause: %s", TypeUtil.errorSerializeToString(object), ex.toString()));
            return null;
        }
    }
}
