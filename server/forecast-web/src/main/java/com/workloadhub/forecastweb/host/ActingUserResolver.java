package com.workloadhub.forecastweb.host;

import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Fills every controller parameter of type {@link ActingUser} from the {@code X-Acting-User} header. A handler
 * without such a parameter needs no header (only {@code GET /api/system}). A blank or malformed header is
 * {@code ACTING_USER_MISSING}; a well-formed id of nobody is {@code ACTING_USER_UNKNOWN}.
 */
public final class ActingUserResolver implements HandlerMethodArgumentResolver {

    private final Directory directory;

    public ActingUserResolver(Directory directory) {
        this.directory = directory;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ActingUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        String value = webRequest.getHeader(ActingUserException.HEADER);
        if (value == null || value.isBlank()) {
            throw ActingUserException.missing();
        }
        UUID id;
        try {
            id = UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw ActingUserException.missing();
        }
        return directory.user(id).orElseThrow(() -> ActingUserException.unknown(value.trim()));
    }
}
