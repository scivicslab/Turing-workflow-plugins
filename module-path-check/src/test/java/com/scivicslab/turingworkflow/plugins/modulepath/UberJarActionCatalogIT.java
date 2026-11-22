package com.scivicslab.turingworkflow.plugins.modulepath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Listing the actions of actors whose classes are in one uber-jar: every listed action of every plugin
 * comes with its prose, and no list is empty.
 *
 * <p>This is the path every action list takes (Turing-workflow's {@code ActionCatalog}, the editor and
 * the chat-ui Actions tab): the class is known from a registered actor, {@code ActionCatalog.actionNamesOf}
 * lists its actions, and {@code ActionManifest.docFor} gives the prose of each.</p>
 *
 * <p>The uber-jar is every jar on this test's classpath merged the way the Quarkus uber-jar builder
 * merges them: of two entries with the same name the first is kept, except under
 * {@code META-INF/services/}, whose files are concatenated. The classes are loaded from that jar alone,
 * so prose counts only when it survived the merge. The calls are reflective because the classes are the
 * merged jar's copies, not this test's.</p>
 */
@DisplayName("Action lists from an uber-jar — every plugin's actions, each with its prose")
class UberJarActionCatalogIT {

    /** Actor classes from three jars, each with the actions a workflow calls. */
    private static final List<String> ACTOR_CLASSES = List.of(
            "com.scivicslab.turingworkflow.plugins.text.TextsIIAR",
            "com.scivicslab.turingworkflow.plugins.ssh.NodeActor",
            "com.scivicslab.turingworkflow.workflow.OutActor");

    @TempDir
    Path dir;

    /** Writes one jar holding the entries of {@code jars}, merged as the Quarkus uber-jar builder does. */
    private static Path merge(Path target, List<Path> jars) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> e = jf.entries();
                while (e.hasMoreElements()) {
                    JarEntry entry = e.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF")
                            || name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) continue;
                    byte[] bytes;
                    try (InputStream in = jf.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    if (name.startsWith("META-INF/services/") && entries.containsKey(name)) {
                        ByteArrayOutputStream joined = new ByteArrayOutputStream();
                        joined.write(entries.get(name));
                        joined.write('\n');
                        joined.write(bytes);
                        entries.put(name, joined.toByteArray());
                    } else {
                        entries.putIfAbsent(name, bytes);
                    }
                }
            }
        }
        try (OutputStream out = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(out)) {
            for (var e : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        return target;
    }

    /** Every jar on the classpath, in classpath order: the plugins, turing-workflow, pojo-actor and their libraries. */
    private static List<Path> classpathJars() {
        List<Path> jars = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.endsWith(".jar")) jars.add(Path.of(entry));
        }
        return jars;
    }

    @Test
    @DisplayName("text, ssh and turing-workflow actors in one uber-jar: each lists actions, each action has prose")
    void uberJar_actionList_everyPluginsActionsWithProse() throws Exception {
        Path merged = merge(dir.resolve("uber.jar"), classpathJars());
        try (URLClassLoader uber = new URLClassLoader(new URL[] {merged.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> catalog = uber.loadClass("com.scivicslab.pojoactor.action.schema.ActionCatalog");
            Class<?> manifestType = uber.loadClass("com.scivicslab.pojoactor.action.schema.ActionManifest");
            Object manifest = manifestType.getConstructor(ClassLoader.class).newInstance(uber);
            Method actionNamesOf = catalog.getMethod("actionNamesOf", Class.class);
            Method docFor = manifestType.getMethod("docFor", Class.class, String.class);

            List<Executable> checks = new ArrayList<>();
            for (String className : ACTOR_CLASSES) {
                Class<?> actor = uber.loadClass(className);
                assertSame(uber, actor.getClassLoader(), className + " comes from the uber-jar");
                Collection<?> names = (Collection<?>) actionNamesOf.invoke(null, actor);
                checks.add(() -> assertFalse(names.isEmpty(), className + " lists no actions"));
                for (Object name : names) {
                    checks.add(() -> assertNotNull(docFor.invoke(manifest, actor, name),
                            className + "." + name + " is listed but has no prose"));
                }
            }
            assertAll(checks);
        }
    }
}
