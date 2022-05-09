package io.arex.api.instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

public interface TypeInstrumentation {

    ElementMatcher<TypeDescription> typeMatcher();

    List<MethodInstrumentation> methodAdvices();

    default AgentBuilder.Transformer transform() {
        return null;
    }

    default DynamicType.Builder test(AgentBuilder builder) {
        return null;
    }
}
