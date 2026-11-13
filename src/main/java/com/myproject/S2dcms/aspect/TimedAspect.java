package com.myproject.S2dcms.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * AOP aspect that intercepts {@link Timed}-annotated methods, records execution
 * time as a Micrometer timer metric, and logs slow invocations.
 *
 * Metrics are tagged with {@code application}, {@code method}, and {@code class}
 * so Prometheus/Grafana can break down p95 latency per endpoint.
 */
@Aspect
@Component
public class TimedAspect {

    private static final Logger log = LoggerFactory.getLogger(TimedAspect.class);

    private final MeterRegistry meterRegistry;

    public TimedAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(timed)")
    public Object around(ProceedingJoinPoint pjp, Timed timed) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();

        String metricName = timed.value().isEmpty()
                ? method.getName()
                : timed.value();

        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = method.getName();

        Tags tags = Tags.of(
                "method", methodName,
                "class", className
        );

        long start = System.currentTimeMillis();
        try {
            return pjp.proceed();
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            meterRegistry.timer("app.method.duration", tags).record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (durationMs > timed.warnThresholdMs()) {
                log.warn("Slow method: {}.{} took {}ms (threshold {}ms)",
                        className, methodName, durationMs, timed.warnThresholdMs());
            }
        }
    }
}