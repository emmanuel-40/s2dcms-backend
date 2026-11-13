package com.myproject.S2dcms.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods for timing. The {@link TimedAspect} records execution
 * time as a Micrometer metric tagged by class and method name, enabling p95
 * monitoring per endpoint via Prometheus.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Timed {
    /**
     * Human-readable name for the metric. Defaults to the method name.
     */
    String value() default "";

    /**
     * Whether to log a warning when execution exceeds this threshold (ms).
     * Default 500ms.
     */
    long warnThresholdMs() default 500;
}