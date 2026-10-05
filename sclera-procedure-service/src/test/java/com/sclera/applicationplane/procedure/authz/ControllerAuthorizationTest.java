package com.sclera.applicationplane.procedure.authz;

import com.sclera.applicationplane.procedure.controller.ProcedureTemplateController;
import com.sclera.applicationplane.procedure.controller.ResultTypeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sclera.applicationplane.procedure.support.AuthorizationModelFiles.jsonRelations;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every permission a controller asks for is one the model defines.
 *
 * AuthorizationModelIT proves the model answers as intended, but it cannot see
 * that {@code @PreAuthorize("@fga.checkOrg('can_manage_result_types')")} spells
 * the relation the way the model does. A typo there passes every other test
 * and, because checks fail closed, turns into a 403 for everyone in production.
 * So this reads the annotations themselves and holds every name in them up
 * against authorization-model.json.
 */
class ControllerAuthorizationTest {

    private static final String BASE_PACKAGE = "com.sclera.applicationplane.procedure";

    private static final Pattern CHECK_ORG = Pattern.compile("@fga\\.checkOrg\\('([^']+)'\\)");
    private static final Pattern CHECK = Pattern.compile("@fga\\.check\\('([^']+)'\\s*,[^,)]*,\\s*'([^']+)'\\)");
    private static final Pattern ANY_FGA_CALL = Pattern.compile("@fga\\.");

    @Test
    void everyEndpointIsGuarded() {
        List<Method> endpoints = endpoints();
        assertThat(endpoints).extracting(Method::getDeclaringClass)
                .contains(ProcedureTemplateController.class, ResultTypeController.class);

        assertThat(endpoints).filteredOn(endpoint -> guard(endpoint) == null)
                .extracting(ControllerAuthorizationTest::name)
                .as("endpoints with no @PreAuthorize")
                .isEmpty();
    }

    @Test
    void everyPermissionAControllerChecksExistsInTheModel() {
        Map<String, Set<String>> model = jsonRelations();
        List<String> problems = new ArrayList<>();
        int checks = 0;
        for (Method endpoint : endpoints()) {
            String expression = guard(endpoint);
            if (expression == null) {
                continue;
            }
            // An expression can join several checks with "and" — read them all, not the first.
            int read = 0;
            Matcher org = CHECK_ORG.matcher(expression);
            while (org.find()) {
                read++;
                require(model, "organization", org.group(1), endpoint, problems);
            }
            Matcher object = CHECK.matcher(expression);
            while (object.find()) {
                read++;
                require(model, object.group(1), object.group(2), endpoint, problems);
            }
            if (read != count(ANY_FGA_CALL, expression)) {
                problems.add(name(endpoint) + " calls @fga in a form this test cannot read: " + expression);
            }
            checks += read;
        }
        assertThat(checks).as("permission checks found").isPositive();
        assertThat(problems).isEmpty();
    }

    @Test
    void resultTypesAreReadByMembersAndChangedOnlyByResultTypeManagers() {
        for (Method endpoint : endpoints()) {
            if (endpoint.getDeclaringClass() != ResultTypeController.class) {
                continue;
            }
            String expected = isGet(endpoint)
                    ? "@fga.checkOrg('can_view')"
                    : "@fga.checkOrg('can_manage_result_types')";
            assertThat(guard(endpoint)).as(name(endpoint)).isEqualTo(expected);
        }
    }

    private static void require(Map<String, Set<String>> model, String type, String relation,
                                Method endpoint, List<String> problems) {
        if (!model.containsKey(type)) {
            problems.add(name(endpoint) + " checks type '" + type + "', which the model does not define");
        } else if (!model.get(type).contains(relation)) {
            problems.add(name(endpoint) + " checks '" + relation + "' on " + type
                    + ", which the model does not define");
        }
    }

    /** Every request-handling method of every @RestController in the service. */
    private static List<Method> endpoints() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Method> endpoints = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = load(candidate.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    endpoints.add(method);
                }
            }
        }
        endpoints.sort(Comparator.comparing(ControllerAuthorizationTest::name));
        return endpoints;
    }

    /** The endpoint's @PreAuthorize expression, or its class's if the method has none. */
    private static String guard(Method endpoint) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(endpoint, PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(endpoint.getDeclaringClass(), PreAuthorize.class);
        }
        return annotation == null ? null : annotation.value();
    }

    private static boolean isGet(Method endpoint) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(endpoint, RequestMapping.class);
        return mapping != null && Arrays.asList(mapping.method()).contains(RequestMethod.GET);
    }

    private static int count(Pattern pattern, String text) {
        return (int) pattern.matcher(text).results().count();
    }

    private static String name(Method endpoint) {
        return endpoint.getDeclaringClass().getSimpleName() + "." + endpoint.getName();
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
