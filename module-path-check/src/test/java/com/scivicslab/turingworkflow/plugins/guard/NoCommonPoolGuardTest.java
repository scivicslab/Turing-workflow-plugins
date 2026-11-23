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
 * No code under any module's {@code src/main/java} runs work on the ForkJoinPool commonPool real threads.
 *
 * <p>The only pool of real threads is the ManagedThreadPool. Work that lands on commonPool real
 * threads is invisible to it: the pool's inline rule cannot see that the work came from a pool real
 * thread, and a pool real thread that waits for that work can hang the pool. This test reads the
 * sources and fails, naming {@code file:line} for each, on:</p>
 * <ul>
 *   <li>a call of a {@code CompletableFuture} {@code *Async} method without its executor argument;</li>
 *   <li>a call of any other {@code *Async} method, whose executor this test cannot tell;</li>
 *   <li>{@code parallelStream()};</li>
 *   <li>{@code commonPool()}, as in {@code ForkJoinPool.commonPool()}.</li>
 * </ul>
 * <p>Nothing is exempted. Comments and string literals are not read as code.</p>
 */
@DisplayName("src/main/java never reaches the commonPool real threads")
class NoCommonPoolGuardTest {

    @Test
    @DisplayName("no *Async call without an executor, no parallelStream(), no commonPool()")
    void mainSourcesStayOffCommonPool() throws IOException {
        // This module is built last, in the repository's top directory beside every module.
        Path top = Path.of("").toAbsolutePath().getParent();
        List<Path> roots;
        try (Stream<Path> modules = Files.list(top)) {
            roots = modules.map(m -> Path.of("..", m.getFileName().toString(), "src", "main", "java"))
                .filter(Files::isDirectory).sorted().toList();
        }
        assertTrue(roots.size() > 1, "no module sources found beside " + Path.of("").toAbsolutePath());
        List<String> hits = CommonPoolScan.scan(roots);
        assertTrue(hits.isEmpty(), hits.size() + " hit(s):\n" + String.join("\n", hits));
    }

    /** Reads Java sources and lists the places that would put work on commonPool real threads. */
    static final class CommonPoolScan {

        /**
         * The CompletableFuture {@code *Async} methods and the number of arguments each takes
         * without its executor; the variant that takes an executor has one more.
         */
        private static final Map<String, Integer> ARGUMENTS_WITHOUT_EXECUTOR = Map.ofEntries(
            Map.entry("runAsync", 1),
            Map.entry("supplyAsync", 1),
            Map.entry("thenApplyAsync", 1),
            Map.entry("thenAcceptAsync", 1),
            Map.entry("thenRunAsync", 1),
            Map.entry("thenComposeAsync", 1),
            Map.entry("handleAsync", 1),
            Map.entry("whenCompleteAsync", 1),
            Map.entry("exceptionallyAsync", 1),
            Map.entry("exceptionallyComposeAsync", 1),
            Map.entry("completeAsync", 1),
            Map.entry("thenCombineAsync", 2),
            Map.entry("thenAcceptBothAsync", 2),
            Map.entry("runAfterBothAsync", 2),
            Map.entry("applyToEitherAsync", 2),
            Map.entry("acceptEitherAsync", 2),
            Map.entry("runAfterEitherAsync", 2));

        private static final Pattern ASYNC = Pattern.compile("\\b(\\w+Async)\\s*\\(");
        private static final Pattern PARALLEL_STREAM = Pattern.compile("\\bparallelStream\\s*\\(");
        private static final Pattern COMMON_POOL = Pattern.compile("\\bcommonPool\\s*\\(");

        private CommonPoolScan() {
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

            Matcher async = ASYNC.matcher(code);
            while (async.find()) {
                if (!isCall(code, async.start())) {
                    continue;  // a method declaration, not a call
                }
                String name = async.group(1);
                if (name.equals("sendAsync")) {
                    continue;  // HttpClient's: it runs on the client's executor, checked where the client is built
                }
                int arguments = countArguments(code, async.end() - 1);
                Integer withoutExecutor = ARGUMENTS_WITHOUT_EXECUTOR.get(name);
                if (withoutExecutor == null) {
                    hits.add(hit(file, code, async.start(), lines,
                        name + "(...) with " + arguments + " argument(s): not a CompletableFuture method, executor unknown"));
                } else if (arguments != withoutExecutor + 1) {
                    hits.add(hit(file, code, async.start(), lines,
                        name + "(...) with " + arguments + " argument(s): no executor argument"));
                }
            }
            Matcher parallel = PARALLEL_STREAM.matcher(code);
            while (parallel.find()) {
                hits.add(hit(file, code, parallel.start(), lines, "parallelStream()"));
            }
            Matcher common = COMMON_POOL.matcher(code);
            while (common.find()) {
                hits.add(hit(file, code, common.start(), lines, "commonPool()"));
            }
        }

        private static String hit(Path file, String code, int offset, String[] lines, String what) {
            int line = 1;
            for (int i = 0; i < offset; i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            return file + ":" + line + ": " + what + " | " + lines[line - 1].strip();
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
