package io.arex.inst.database.hibernate;

import io.arex.api.instrumentation.ModuleInstrumentation;
import io.arex.api.instrumentation.TypeInstrumentation;
import com.google.auto.service.AutoService;

import java.util.List;

import static java.util.Arrays.asList;

@AutoService(ModuleInstrumentation.class)
public class HibernateModuleInstrumentation extends ModuleInstrumentation {
    public HibernateModuleInstrumentation() {
        super("hibernate");
    }

    @Override
    public List<TypeInstrumentation> instrumentationTypes() {
        return asList(new LoaderInstrumentation(),
                new AbstractProducedQueryInstrumentation(),
                new AbstractEntityPersisterInstrumentation());
    }
}
