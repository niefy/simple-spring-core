package com.github.niefy.web.annotation;

import java.lang.annotation.*;

/**
 * mvc controller
 *
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestController {
    String value() default "";
}
