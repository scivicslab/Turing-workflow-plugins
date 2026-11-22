package com.scivicslab.turingworkflow.plugins.modulepath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every published jar can be put on the module path: each one becomes an automatic module under the
 * name its manifest declares, and all of them resolve together.
 *
 * <p>The jars are the ones Maven packaged for this build: they are on this test's classpath because
 * failsafe runs after the package phase of every module this one depends on.</p>
 */
@DisplayName("Published jars — automatic modules with declared names, resolved together")
class AutomaticModuleNameIT {

    /** Artifact id to the module name its manifest declares. */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("pojo-actor", "com.scivicslab.pojoactor");
        EXPECTED.put("turing-workflow", "com.scivicslab.turingworkflow");
        String plugins = "com.scivicslab.turingworkflow.plugins.";
        EXPECTED.put("plugin-llm", plugins + "llm");
        EXPECTED.put("plugin-prompt-builder", plugins + "promptbuilder");
        EXPECTED.put("plugin-secret", plugins + "secret");
        EXPECTED.put("plugin-vault", plugins + "vault");
        EXPECTED.put("plugin-log-db", plugins + "logdb");
        EXPECTED.put("plugin-log-output", plugins + "logoutput");
        EXPECTED.put("plugin-kana-kanji", plugins + "kanakanji");
        EXPECTED.put("plugin-report", plugins + "report");
        EXPECTED.put("plugin-ssh", plugins + "ssh");
        EXPECTED.put("plugin-inventory", plugins + "inventory");
        EXPECTED.put("plugin-web", plugins + "web");
        EXPECTED.put("plugin-chatui3", plugins + "chatui3");
        EXPECTED.put("plugin-fineweb-search", plugins + "finewebsearch");
        EXPECTED.put("plugin-openalex", plugins + "openalex");
        EXPECTED.put("plugin-arxiv", plugins + "arxiv");
        EXPECTED.put("plugin-semantic-scholar", plugins + "semanticscholar");
        EXPECTED.put("plugin-ocr", plugins + "ocr");
        EXPECTED.put("plugin-codedoc", plugins + "codedoc");
        EXPECTED.put("plugin-rst-frame", plugins + "rstframe");
        EXPECTED.put("plugin-text", plugins + "text");
    }

    /** The packaged jar of {@code artifactId} on the classpath: {@code <artifactId>-<version>.jar}. */
    private static Path jarOf(String artifactId) {
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p = Path.of(entry);
            String file = p.getFileName().toString();
            String prefix = artifactId + "-";
            if (file.endsWith(".jar") && file.startsWith(prefix)
                    && Character.isDigit(file.charAt(prefix.length()))
                    && !file.endsWith("-sources.jar") && !file.endsWith("-javadoc.jar")
                    && !file.endsWith("-shaded.jar")) {
                return p;
            }
        }
        return null;
    }

    @Test
    @DisplayName("each jar alone is an automatic module named as declared")
    void eachJar_onModulePath_isAutomaticModuleWithDeclaredName() {
        List<Executable> checks = new ArrayList<>();
        for (var e : EXPECTED.entrySet()) {
            checks.add(() -> {
                Path jar = jarOf(e.getKey());
                assertNotNull(jar, e.getKey() + ": its jar is not on the classpath");
                Set<ModuleReference> found;
                try {
                    found = ModuleFinder.of(jar).findAll();
                } catch (RuntimeException ex) {
                    fail(e.getKey() + ": " + jar.getFileName() + " is not a module: " + ex.getMessage());
                    return;
                }
                assertEquals(1, found.size(), e.getKey() + ": modules found in " + jar);
                ModuleDescriptor d = found.iterator().next().descriptor();
                assertTrue(d.isAutomatic(), e.getKey() + ": not an automatic module");
                assertEquals(e.getValue(), d.name(), e.getKey() + ": module name");
            });
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("all the jars resolve together: unique names, no package in two of them")
    void allJars_resolvedTogether_formOneConfiguration() {
        List<Path> jars = EXPECTED.keySet().stream().map(AutomaticModuleNameIT::jarOf).toList();
        assertTrue(jars.stream().allMatch(java.util.Objects::nonNull), "every jar is on the classpath: " + jars);
        Configuration cf;
        try {
            cf = ModuleLayer.boot().configuration().resolve(
                    ModuleFinder.of(jars.toArray(Path[]::new)), ModuleFinder.of(), Set.copyOf(EXPECTED.values()));
        } catch (RuntimeException ex) {
            fail("the jars do not resolve together: " + ex);
            return;
        }
        Set<String> resolved = cf.modules().stream().map(ResolvedModule::name).collect(Collectors.toSet());
        assertEquals(Set.copyOf(EXPECTED.values()), resolved);
    }
}
