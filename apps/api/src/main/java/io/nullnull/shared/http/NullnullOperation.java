package io.nullnull.shared.http;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/** Public operation identity and its OpenAPI security requirements. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NullnullOperation {
    String id();
    Security[] security() default {};
    enum Security { SESSION, CSRF }
}
