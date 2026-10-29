package com.akshatha.urlshortener.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Marks a string as "safe to shorten". See SafeUrlValidator. */
@Documented
@Constraint(validatedBy = SafeUrlValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface SafeUrl {

    String message() default "must be a public http(s) URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
