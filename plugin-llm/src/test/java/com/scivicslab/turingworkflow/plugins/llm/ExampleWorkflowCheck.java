package com.scivicslab.turingworkflow.plugins.llm;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.MatrixCode;
import com.scivicslab.turingworkflow.workflow.Transition;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;
import jakarta.validation.constraints.NotNull;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks an example workflow without running it: the YAML parses, every actor it calls is one the run CLI provides
 * or one the workflow creates with {@code loader.createChild}, every method is an {@code @Action} of that actor, and
 * an action that declares {@code argsType} receives a map with that record's fields.
 */
final class ExampleWorkflowCheck {

    /** Actions of the loader actor, which dispatches by name rather than through {@code @Action}. */
    private static final Set<String> LOADER_ACTIONS = Set.of(
            "loadJar", "createChild", "removeChild", "listLoadedJars",
            "createFromProvider", "listProviders", "loadProvidersFromJar");

    private ExampleWorkflowCheck() {
    }

    /** Parses the workflow at a class-path resource. */
    static MatrixCode parse(String resource) throws Exception {
        try (InputStream in = ExampleWorkflowCheck.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException("resource not found: " + resource);
            IIActorSystem system = new IIActorSystem("example-check");
            try {
                Interpreter interpreter = new Interpreter.Builder()
                        .loggerName("example-check").team(system).build();
                interpreter.readYaml(in);
                return interpreter.getCode();
            } finally {
                system.terminate();
            }
        }
    }

    /** Returns one line per action whose actor, method or argument form does not exist; empty when all do. */
    static List<String> problems(MatrixCode code) throws ClassNotFoundException {
        Map<String, Class<?>> actors = new HashMap<>();
        actors.put("interpreter", InterpreterIIAR.class);
        actors.put(MultiplexerAccumulatorIIAR.ACTOR_NAME, MultiplexerAccumulatorIIAR.class);
        List<String> problems = new ArrayList<>();
        for (Transition t : code.getTransitions()) {
            for (com.scivicslab.turingworkflow.workflow.Action a : t.getActions()) {
                String where = t.getStates() + " " + a.getActor() + "." + a.getMethod() + ": ";
                if (a.getActor().equals("loader")) {
                    if (!LOADER_ACTIONS.contains(a.getMethod())) problems.add(where + "no such loader action");
                    if (a.getMethod().equals("createChild") && a.getArguments() instanceof List<?> args
                            && args.size() == 3) {
                        actors.put(String.valueOf(args.get(1)), Class.forName(String.valueOf(args.get(2))));
                    }
                    continue;
                }
                Class<?> type = actors.get(a.getActor());
                if (type == null) {
                    problems.add(where + "actor neither provided by the run CLI nor created before");
                    continue;
                }
                Action declared = findAction(type, a.getMethod());
                if (declared == null) {
                    problems.add(where + "no @Action of that name on " + type.getSimpleName());
                } else if (declared.argsType() != Void.class) {
                    problems.addAll(argumentProblems(where, declared.argsType(), a.getArguments()));
                }
            }
        }
        return problems;
    }

    private static Action findAction(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            Action action = m.getAnnotation(Action.class);
            if (action != null && action.value().equals(name)) return action;
        }
        return null;
    }

    private static List<String> argumentProblems(String where, Class<?> argsType, Object arguments) {
        List<String> problems = new ArrayList<>();
        if (!(arguments instanceof Map<?, ?> given)) {
            problems.add(where + "takes a map with the fields of " + argsType.getSimpleName() + ", got " + arguments);
            return problems;
        }
        Set<String> fields = new java.util.HashSet<>();
        for (RecordComponent c : argsType.getRecordComponents()) {
            fields.add(c.getName());
            boolean required = c.getAccessor().isAnnotationPresent(NotNull.class)
                    || c.isAnnotationPresent(NotNull.class);
            if (required && !given.containsKey(c.getName())) {
                problems.add(where + "missing required field " + c.getName());
            }
        }
        for (Object key : given.keySet()) {
            if (!fields.contains(String.valueOf(key))) problems.add(where + "no field " + key);
        }
        return problems;
    }
}
