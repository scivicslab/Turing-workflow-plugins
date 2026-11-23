/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.plugins.guard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No code under any module's {@code src/main/java} makes a real (platform) thread outside the system's one
 * ManagedThreadPool ({@code RealThreadsOutsideManagedThreadPool_261122_oo01}).
 *
 * <p>Real threads outside that pool are outside POJO-actor's control: nobody knows how many there
 * are or whose work they run, and the pool's inline rule cannot recognise them. This test reads the
 * sources, with comments and string literals blanked, and fails naming {@code file:line} for each:</p>
 * <ul>
 *   <li>a pool of real threads: {@code Executors.newFixedThreadPool}, {@code newCachedThreadPool},
 *       {@code newScheduledThreadPool}, {@code newSingleThreadExecutor},
 *       {@code newSingleThreadScheduledExecutor}, {@code newWorkStealingPool};
 *       {@code new ThreadPoolExecutor}, {@code new ScheduledThreadPoolExecutor},
 *       {@code new ForkJoinPool}, and a class that extends one of them;</li>
 *   <li>the commonPool real threads: {@code commonPool()}, {@code parallelStream()}, a
 *       {@code CompletableFuture} {@code *Async} call without its executor argument, and any other
 *       {@code *Async} call, whose executor this test cannot tell, except {@code sendAsync}: that is
 *       {@code HttpClient}'s, it runs on the client's executor, and the {@code HttpClient} rule
 *       below checks that executor where the client is built;</li>
 *   <li>a real thread started by hand: {@code new Thread(}, {@code Thread.ofPlatform()}, and a
 *       class that extends {@code Thread};</li>
 *   <li>a JDK {@code HttpClient}, which runs on a pool of real threads of its own unless it is given
 *       an executor: {@code HttpClient.newHttpClient()}, and a {@code HttpClient.newBuilder()}
 *       statement without {@code .executor(}.</li>
 * </ul>
 * <p>{@code Executors.newVirtualThreadPerTaskExecutor()} and {@code Thread.ofVirtual()} make
 * virtual threads and are allowed. The exemptions are listed in {@link #EXEMPT}, each with its
 * reason; nothing else is exempted.</p>
 */
@DisplayName("src/main/java makes no real thread outside the ManagedThreadPool")
class RealThreadsOnlyInManagedThreadPoolGuardTest {

    /** An exempted place: a source file (path suffix), the code on its line, and why. */
    record Exemption(String file, String code, String reason) {}

    /** The only places allowed to make real threads. */
    static final List<Exemption> EXEMPT = List.of(
        // The system's one pool of real threads is this class: it extends ThreadPoolExecutor and makes
        // its real threads as PoolThreads. Every other real thread must come from it.
        new Exemption("/com/scivicslab/pojoactor/core/ManagedThreadPool.java", "",
            "the ManagedThreadPool class itself, the one pool of real threads"),
        // Runtime.addShutdownHook takes an unstarted Thread object, which the JDK starts at exit; there
        // is no other way to register a shutdown hook.
        new Exemption("/LogServeCLI.java", "addShutdownHook(new Thread(",
            "a shutdown hook, which the JDK requires as a Thread object"));

    @Test
    @DisplayName("no real thread pool, commonPool use, hand-started real thread or HttpClient without an executor")
    void mainSourcesMakeNoRealThreads() throws IOException {
        // This module is built last, in the repository's top directory beside every module.
        Path top = Path.of("").toAbsolutePath().getParent();
        List<Path> roots;
        try (Stream<Path> modules = Files.list(top)) {
            roots = modules.map(m -> Path.of("..", m.getFileName().toString(), "src", "main", "java"))
                .filter(Files::isDirectory).sorted().toList();
        }
        assertTrue(roots.size() > 1, "no module sources found beside " + Path.of("").toAbsolutePath());
        List<String> hits = RealThreadScan.scan(roots);
        assertTrue(hits.isEmpty(), hits.size() + " hit(s):\n" + String.join("\n", hits));
    }

    /** Reads Java sources and lists the places that make real threads outside the ManagedThreadPool. */
    static final class RealThreadScan {

        /** A form that makes real threads, found by a pattern on the code. */
        record Rule(Pattern pattern, String what) {}

        static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\b(newFixedThreadPool|newCachedThreadPool|newScheduledThreadPool"
                + "|newSingleThreadExecutor|newSingleThreadScheduledExecutor|newWorkStealingPool)\\s*\\("),
                "a pool of real threads"),
            new Rule(Pattern.compile("\\bnew\\s+(ThreadPoolExecutor|ScheduledThreadPoolExecutor|ForkJoinPool)\\s*\\("),
                "a pool of real threads"),
            new Rule(Pattern.compile("\\bextends\\s+(ThreadPoolExecutor|ScheduledThreadPoolExecutor|ForkJoinPool|Thread)\\b"),
                "a class of real threads or of a pool of them"),
            new Rule(Pattern.compile("\\bcommonPool\\s*\\("), "the commonPool real threads"),
            new Rule(Pattern.compile("\\bparallelStream\\s*\\("), "the commonPool real threads"),
            new Rule(Pattern.compile("\\bnew\\s+Thread\\s*\\("), "a real thread started by hand"),
            new Rule(Pattern.compile("\\bThread\\s*\\.\\s*ofPlatform\\s*\\("), "a real thread started by hand"),
            new Rule(Pattern.compile("\\bHttpClient\\s*\\.\\s*newHttpClient\\s*\\("),
                "an HttpClient on a pool of real threads of its own"));

        /**
         * The CompletableFuture {@code *Async} methods and the number of arguments each takes
         * without its executor; the variant that takes an executor has one more.
         */
        static final Map<String, Integer> ARGUMENTS_WITHOUT_EXECUTOR = Map.ofEntries(
            Map.entry("runAsync", 1), Map.entry("supplyAsync", 1), Map.entry("thenApplyAsync", 1),
            Map.entry("thenAcceptAsync", 1), Map.entry("thenRunAsync", 1), Map.entry("thenComposeAsync", 1),
            Map.entry("handleAsync", 1), Map.entry("whenCompleteAsync", 1), Map.entry("exceptionallyAsync", 1),
            Map.entry("exceptionallyComposeAsync", 1), Map.entry("completeAsync", 1),
            Map.entry("thenCombineAsync", 2), Map.entry("thenAcceptBothAsync", 2), Map.entry("runAfterBothAsync", 2),
            Map.entry("applyToEitherAsync", 2), Map.entry("acceptEitherAsync", 2), Map.entry("runAfterEitherAsync", 2));

        static final Pattern ASYNC = Pattern.compile("\\b(\\w+Async)\\s*\\(");
        static final Pattern HTTP_BUILDER = Pattern.compile("\\bHttpClient\\s*\\.\\s*newBuilder\\s*\\(");
        static final Pattern EXECUTOR_CALL = Pattern.compile("\\.\\s*executor\\s*\\(");

        private RealThreadScan() {
        }

        static List<String> scan(List<Path> roots) throws IOException {
            List<String> hits = new ArrayList<>();
            for (Path root : roots) {
                if (!Files.isDirectory(root)) {
                    hits.add(root + ": not a directory, nothing was read");
                    continue;
                }
                List<Path> files;
                try (Stream<Path> walk = Files.walk(root)) {
                    files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
                }
                for (Path file : files) {
                    scanFile(file, hits);
                }
            }
            return hits;
        }

        private static void scanFile(Path file, List<String> hits) throws IOException {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String code = blankCommentsAndLiterals(source);
            String[] lines = source.split("\n", -1);
            List<Integer> offsets = new ArrayList<>();
            List<String> what = new ArrayList<>();

            for (Rule rule : RULES) {
                Matcher m = rule.pattern().matcher(code);
                while (m.find()) {
                    offsets.add(m.start());
                    what.add(rule.what() + ": " + m.group().replaceAll("\\s+", " ").trim());
                }
            }
            Matcher async = ASYNC.matcher(code);
            while (async.find()) {
                if (!isCall(code, async.start())) {
                    continue;  // a method declaration, not a call
                }
                String name = async.group(1);
                if (name.equals("sendAsync")) {
                    continue;  // HttpClient's: its executor is checked where the client is built
                }
                int arguments = countArguments(code, async.end() - 1);
                Integer withoutExecutor = ARGUMENTS_WITHOUT_EXECUTOR.get(name);
                if (withoutExecutor == null) {
                    offsets.add(async.start());
                    what.add(name + "(...) with " + arguments + " argument(s): not a CompletableFuture method, executor unknown");
                } else if (arguments != withoutExecutor + 1) {
                    offsets.add(async.start());
                    what.add(name + "(...) with " + arguments + " argument(s): no executor argument, the commonPool real threads");
                }
            }
            Matcher builder = HTTP_BUILDER.matcher(code);
            while (builder.find()) {
                int end = statementEnd(code, builder.end());
                if (!EXECUTOR_CALL.matcher(code.substring(builder.start(), end)).find()) {
                    offsets.add(builder.start());
                    what.add("HttpClient.newBuilder() without .executor(: an HttpClient on a pool of real threads of its own");
                }
            }

            String path = file.toString().replace('\\', '/');
            List<String> mine = new ArrayList<>();
            for (int i = 0; i < offsets.size(); i++) {
                int line = lineOf(code, offsets.get(i));
                String text = lines[line - 1].strip();
                if (exempted(path, text)) {
                    continue;
                }
                mine.add(file + ":" + line + ": " + what.get(i) + " | " + text);
            }
            mine.sort(null);
            hits.addAll(mine);
        }

        static boolean exempted(String path, String lineText) {
            for (Exemption e : EXEMPT) {
                if (path.endsWith(e.file()) && lineText.contains(e.code())) {
                    return true;
                }
            }
            return false;
        }

        private static int lineOf(String code, int offset) {
            int line = 1;
            for (int i = 0; i < offset; i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        /** The offset of the {@code ;} that ends the statement the code at {@code from} is in. */
        private static int statementEnd(String code, int from) {
            int depth = 0;
            for (int i = from; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    if (--depth < 0) {
                        return i;
                    }
                } else if (c == ';' && depth == 0) {
                    return i;
                }
            }
            return code.length();
        }

        /**
         * Whether the name at {@code start} is called rather than declared: a call is qualified
         * ({@code x.runAsync(}) or stands where an expression starts; a declaration follows its
         * return type.
         */
        private static boolean isCall(String code, int start) {
            int i = start - 1;
            while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
                i--;
            }
            if (i < 0) {
                return false;
            }
            char before = code.charAt(i);
            if (before == '.') {
                return true;
            }
            if (Character.isJavaIdentifierPart(before)) {
                int end = i + 1;
                while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) {
                    i--;
                }
                String word = code.substring(i + 1, end);
                return word.equals("return") || word.equals("throw") || word.equals("yield");
            }
            return before != '>';  // after a generic return type such as CompletableFuture<Void>
        }

        /** Counts the arguments of the call whose opening parenthesis is at {@code open}. */
        private static int countArguments(String code, int open) {
            int depth = 0;
            int commas = 0;
            boolean any = false;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                    if (depth == 0) {
                        return any ? commas + 1 : 0;
                    }
                } else if (depth == 1 && c == ',') {
                    commas++;
                } else if (depth >= 1 && !Character.isWhitespace(c)) {
                    any = true;
                }
            }
            return any ? commas + 1 : 0;
        }

        /**
         * The source with comments, string and text-block literals and character literals replaced
         * by spaces, newlines kept, so offsets and line numbers stay those of the source.
         */
        static String blankCommentsAndLiterals(String s) {
            StringBuilder out = new StringBuilder(s);
            int i = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    while (i < n && s.charAt(i) != '\n') {
                        out.setCharAt(i++, ' ');
                    }
                } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                    int end = s.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                    blank(out, i, end);
                    i = end;
                } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                    int end = s.indexOf("\"\"\"", i + 3);
                    while (end > 0 && s.charAt(end - 1) == '\\') {
                        end = s.indexOf("\"\"\"", end + 1);
                    }
                    end = end < 0 ? n : end + 3;
                    blank(out, i, end);
                    i = end;
                } else if (c == '"' || c == '\'') {
                    int j = i + 1;
                    while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') {
                        j += s.charAt(j) == '\\' ? 2 : 1;
                    }
                    int end = Math.min(j + 1, n);
                    blank(out, i, end);
                    i = end;
                } else {
                    i++;
                }
            }
            return out.toString();
        }

        private static void blank(StringBuilder out, int from, int to) {
            for (int k = from; k < to; k++) {
                if (out.charAt(k) != '\n') {
                    out.setCharAt(k, ' ');
                }
            }
        }
    }
}
