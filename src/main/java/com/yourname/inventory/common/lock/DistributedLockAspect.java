package com.yourname.inventory.common.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Turns {@link DistributedLock} into behaviour.
 *
 * ORDER IS THE LESSON HERE. Spring's transaction advisor runs at {@code LOWEST_PRECEDENCE}.
 * By giving this aspect a slightly lower number we guarantee:
 *
 *     acquire lock -> BEGIN tx -> method body -> COMMIT -> release lock
 *
 * If the lock were released INSIDE the transaction (i.e. before commit), another request could
 * take the lock and read rows the first transaction has not committed yet - a lost update, the
 * exact bug the lock was supposed to prevent.
 *
 * Second gotcha, free of charge: this is a Spring AOP proxy, so the annotation only works on a
 * PUBLIC method called from ANOTHER bean. {@code this.reserve(...)} inside the same class
 * bypasses the proxy entirely and silently runs without any lock. That is why
 * {@code StockFacade} (locks/retries) and {@code StockService} (transactions) are two beans.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class DistributedLockAspect {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockAspect.class);

    private final RedisLockService lockService;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    public DistributedLockAspect(RedisLockService lockService) {
        this.lockService = lockService;
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String key = resolveKey(joinPoint, distributedLock.key());

        var handle = lockService.acquire(key,
                Duration.ofMillis(distributedLock.waitTimeMs()),
                Duration.ofMillis(distributedLock.leaseTimeMs()));

        if (handle.isEmpty()) {
            if (distributedLock.throwOnFailure()) {
                throw new LockAcquisitionException(key, distributedLock.waitTimeMs());
            }
            log.info("Skipping {} - lock '{}' is busy", joinPoint.getSignature().toShortString(), key);
            return null;
        }

        long start = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            // ALWAYS in a finally block. An exception must not leave the lock held for the rest
            // of its lease - that would block every other request on this key for 10 seconds.
            lockService.release(handle.get());
            log.debug("Critical section '{}' took {}ms", key, (System.nanoTime() - start) / 1_000_000);
        }
    }

    /** Evaluates the SpEL key against the real argument values of this invocation. */
    private String resolveKey(ProceedingJoinPoint joinPoint, String expression) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        EvaluationContext context = new StandardEvaluationContext();

        String[] names = paramNames.getParameterNames(method);
        Object[] args = joinPoint.getArgs();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                context.setVariable(names[i], args[i]);   // #productId
                context.setVariable("p" + i, args[i]);    // #p0, works without -parameters
            }
        }

        Object value = parser.parseExpression(expression).getValue(context);
        if (value == null) {
            throw new IllegalStateException("Lock key expression '" + expression + "' evaluated to null on "
                    + joinPoint.getSignature().toShortString());
        }
        return value.toString();
    }
}
