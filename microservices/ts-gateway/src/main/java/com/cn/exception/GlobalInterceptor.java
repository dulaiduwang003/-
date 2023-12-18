package com.cn.exception;

import cn.dev33.satoken.util.SaResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.List;

/**
 * The type Global interceptor.
 */
@RestControllerAdvice
@SuppressWarnings("all")
public class GlobalInterceptor {


    /**
     * Web exchange bind exception sa result.
     *
     * @param e the e
     * @return the sa result
     */
    @ResponseBody
    @ExceptionHandler(value = WebExchangeBindException.class)
    public SaResult WebExchangeBindException(WebExchangeBindException e) {
        final List<ObjectError> allErrors = e.getBindingResult().getAllErrors();
        return SaResult.error((allErrors.get(0).getDefaultMessage()));
    }

}
