package com.odel.identity;

import com.odel.common.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Lets controllers take the signed-in session as a parameter: {@code CurrentUserArg.Holder cu}. */
@Component
public class CurrentUserArg implements HandlerMethodArgumentResolver, WebMvcConfigurer {
    public record Holder(CurrentUser user) {}

    public static CurrentUser current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof CurrentUser cu) return cu;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "signed_out", "Please sign in again.");
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Holder.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binder) {
        return new Holder(current());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
