package com.baysansoft.mqmanager.web.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Cross-field rules that only make sense once the provider is known. */
@Documented
@Constraint(validatedBy = ConnectionProfileValidator.class)
@Target({ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidConnectionProfile {

    String message() default "Invalid connection profile";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
