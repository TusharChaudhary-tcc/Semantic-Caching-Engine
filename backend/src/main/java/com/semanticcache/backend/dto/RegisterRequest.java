package com.semanticcache.backend.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * Registration request payload containing credentials for new user account creation.
 * <p>
 * Enforces minimum 8 characters and maximum 72 UTF-8 bytes to strictly respect
 * the BCrypt hashing algorithm's native 72-byte limit and prevent silent truncation.
 */
public record RegisterRequest(
        @NotBlank(message = "Username cannot be blank")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,

        @NotBlank(message = "Password cannot be blank")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @MaxBytes(72)
        String password
) {

    /**
     * Jakarta Bean Validation constraint validating that a string's UTF-8 encoded
     * representation does not exceed a specified maximum byte length.
     */
    @Documented
    @Constraint(validatedBy = MaxBytesValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MaxBytes {
        String message() default "Password must not exceed {value} bytes in UTF-8 encoding";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        int value();
    }

    public static class MaxBytesValidator implements ConstraintValidator<MaxBytes, String> {
        private int maxBytes;

        @Override
        public void initialize(MaxBytes constraintAnnotation) {
            this.maxBytes = constraintAnnotation.value();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
        }
    }
}
