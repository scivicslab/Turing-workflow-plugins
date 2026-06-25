/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.scivicslab.turingworkflow.plugins.codedoc;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.json.JSONArray;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deterministic actors for the code-understanding / document-generation workflows: scan a project,
 * split a document into chunks, and write a list of strings to a file. These are the "確実に既知問題を"
 * pieces — pure rules, no LLM — that surround the {@code parallel-map} + {@code llm} stages.
 *
 * <p>To avoid passing large multi-line content through workflow arguments (which breaks JSON/escaping),
 * the actors read and write {@code list:} actors and files <b>by name</b>: they reach into the
 * {@link IIActorSystem} themselves rather than receiving the content as a string argument.</p>
 *
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@code chunkFile} — {@code [inFile, list:out, maxChars?]}: split a document into Markdown-aware
 *       chunks and append each to the named list. Result = chunk count.</li>
 *   <li>{@code writeFileFromList} — {@code [outFile, list:src, separator?]}: join the list's items and
 *       write them to a file. Result = the output path.</li>
 *   <li>{@code scanProject} — {@code [projectDir, list:out, maxFileChars?]}: enumerate source files
 *       (skipping build/VCS/binary noise) and append one {@code FILE: path\n\n<content>} payload per
 *       file to the named list. Result = file count.</li>
 *   <li>{@code joinListToStr} — {@code [list:src, str:dest, separator?]}: join the list's items and
 *       store the result in the named {@code str:} actor (the reduce-input bridge: feed many summaries
 *       into one {@code llm} call without passing huge content through arguments). Result = item count.</li>
 *   <li>{@code writeStrToFile} — {@code [outFile, str:src]}: write the named {@code str:} actor's value
 *       to a file (used to persist a synthesized overview held in a {@code str:} actor). Result = path.</li>
 * </ul>
 */
public class CodeDocActor extends IIActorRef<Object> {

    private static final int DEFAULT_CHUNK_CHARS = 3_000;
    private static final int DEFAULT_FILE_CHARS = 20_000;

    private static final Set<String> SKIP_DIRS = Set.of(
            "target", "build", "dist", "out", "bin", "node_modules",
            ".git", ".gradle", ".idea", ".mvn", ".vscode", ".settings");
    private static final Set<String> SKIP_EXT = Set.of(
            "class", "jar", "war", "ear", "zip", "gz", "tar", "tgz", "so", "o", "a", "dll", "exe",
            "bin", "png", "jpg", "jpeg", "gif", "ico", "pdf", "woff", "woff2", "ttf", "eot",
            "mp4", "mp3", "wav", "lock", "p12", "jks", "keystore",
            "html", "htm", "css", "scss", "less", "map", "db", "cfs", "si");

    private final IIActorSystem system;

    public CodeDocActor(String name, IIActorSystem system) {
        super(name, new Object(), system);
        this.system = system;
    }

    @Action("chunkFile")
    public ActionResult chunkFile(String args) {
        try {
            JSONArray a = new JSONArray(args);
            Path in = Path.of(a.getString(0));
            String listName = a.getString(1);
            int max = a.length() >= 3 ? a.getInt(2) : DEFAULT_CHUNK_CHARS;
            List<String> chunks = chunk(Files.readString(in), max);
            IIActorRef<?> list = system.getIIActor(listName);
            if (list == null) return new ActionResult(false, "chunkFile: list not found: " + listName);
            for (String c : chunks) list.callByActionName("add", c);
            return new ActionResult(true, String.valueOf(chunks.size()));
        } catch (Exception e) {
            return new ActionResult(false, "chunkFile: " + e.getMessage());
        }
    }

    @Action("writeFileFromList")
    public ActionResult writeFileFromList(String args) {
        try {
            JSONArray a = new JSONArray(args);
            Path out = Path.of(a.getString(0));
            String listName = a.getString(1);
            String sep = a.length() >= 3 ? a.getString(2) : "\n\n";
            IIActorRef<?> list = system.getIIActor(listName);
            if (list == null) return new ActionResult(false, "writeFileFromList: list not found: " + listName);
            int n = Integer.parseInt(list.callByActionName("size", "").getResult().trim());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(sep);
                sb.append(list.callByActionName("get", String.valueOf(i)).getResult());
            }
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString());
            return new ActionResult(true, out.toString());
        } catch (Exception e) {
            return new ActionResult(false, "writeFileFromList: " + e.getMessage());
        }
    }

    @Action("joinListToStr")
    public ActionResult joinListToStr(String args) {
        try {
            JSONArray a = new JSONArray(args);
            String listName = a.getString(0);
            String strName = a.getString(1);
            String sep = a.length() >= 3 ? a.getString(2) : "\n\n";
            IIActorRef<?> list = system.getIIActor(listName);
            if (list == null) return new ActionResult(false, "joinListToStr: list not found: " + listName);
            IIActorRef<?> str = system.getIIActor(strName);
            if (str == null) return new ActionResult(false, "joinListToStr: str actor not found: " + strName);
            int n = Integer.parseInt(list.callByActionName("size", "").getResult().trim());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(sep);
                sb.append(list.callByActionName("get", String.valueOf(i)).getResult());
            }
            str.callByActionName("set", sb.toString());
            return new ActionResult(true, String.valueOf(n));
        } catch (Exception e) {
            return new ActionResult(false, "joinListToStr: " + e.getMessage());
        }
    }

    @Action("writeStrToFile")
    public ActionResult writeStrToFile(String args) {
        try {
            JSONArray a = new JSONArray(args);
            Path out = Path.of(a.getString(0));
            String strName = a.getString(1);
            IIActorRef<?> str = system.getIIActor(strName);
            if (str == null) return new ActionResult(false, "writeStrToFile: str actor not found: " + strName);
            String content = str.callByActionName("get", "").getResult();
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, content == null ? "" : content);
            return new ActionResult(true, out.toString());
        } catch (Exception e) {
            return new ActionResult(false, "writeStrToFile: " + e.getMessage());
        }
    }

    @Action("scanProject")
    public ActionResult scanProject(String args) {
        try {
            JSONArray a = new JSONArray(args);
            Path base = Path.of(a.getString(0)).toAbsolutePath().normalize();
            String listName = a.getString(1);
            int maxChars = a.length() >= 3 ? a.getInt(2) : DEFAULT_FILE_CHARS;
            String type = detectType(base);
            IIActorRef<?> list = system.getIIActor(listName);
            if (list == null) return new ActionResult(false, "scanProject: list not found: " + listName);
            int[] count = {0};
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> !isSkipped(base, f))
                    .filter(f -> keepForType(type, base.relativize(f)))
                    .sorted()
                    .forEach(f -> {
                        try {
                            if (Files.size(f) > 1_000_000) return;
                            String c = Files.readString(f);
                            if (c.length() > maxChars) c = c.substring(0, maxChars) + "\n…(truncated)…";
                            list.callByActionName("add", "FILE: " + base.relativize(f) + "\n\n" + c);
                            count[0]++;
                        } catch (Exception ignore) { /* skip unreadable */ }
                    });
            }
            return new ActionResult(true, String.valueOf(count[0]));
        } catch (Exception e) {
            return new ActionResult(false, "scanProject: " + e.getMessage());
        }
    }

    @Action("listMarkdownNoDescription")
    public ActionResult listMarkdownNoDescription(String args) {
        try {
            JSONArray a = new JSONArray(args);
            Path base = Path.of(a.getString(0)).toAbsolutePath().normalize();
            String listName = a.getString(1);
            IIActorRef<?> list = system.getIIActor(listName);
            if (list == null) return new ActionResult(false, "listMarkdownNoDescription: list not found: " + listName);
            int[] count = {0};
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .filter(f -> !isSkipped(base, f))
                    .sorted()
                    .forEach(f -> {
                        try {
                            if (!hasFrontmatterDescription(Files.readString(f))) {
                                list.callByActionName("add", f.toString());
                                count[0]++;
                            }
                        } catch (Exception ignore) { /* skip unreadable */ }
                    });
            }
            return new ActionResult(true, String.valueOf(count[0]));
        } catch (Exception e) {
            return new ActionResult(false, "listMarkdownNoDescription: " + e.getMessage());
        }
    }

    @Action("readFileToStr")
    public ActionResult readFileToStr(String args) {
        try {
            JSONArray a = new JSONArray(args);
            Path in = Path.of(a.getString(0));
            String strName = a.getString(1);
            IIActorRef<?> str = system.getIIActor(strName);
            if (str == null) return new ActionResult(false, "readFileToStr: str actor not found: " + strName);
            str.callByActionName("set", Files.readString(in));
            return new ActionResult(true, in.toString());
        } catch (Exception e) {
            return new ActionResult(false, "readFileToStr: " + e.getMessage());
        }
    }

    @Action("setFrontmatterDescription")
    public ActionResult setFrontmatterDescription(String args) {
        try {
            JSONArray a = new JSONArray(args);
            Path file = Path.of(a.getString(0));
            String strName = a.getString(1);
            IIActorRef<?> str = system.getIIActor(strName);
            if (str == null) return new ActionResult(false, "setFrontmatterDescription: str actor not found: " + strName);
            String desc = str.callByActionName("get", "").getResult();
            desc = desc == null ? "" : desc.strip();
            if (desc.isEmpty()) return new ActionResult(true, "skipped (empty description): " + file);
            String src = Files.readString(file);
            if (hasFrontmatterDescription(src)) {
                return new ActionResult(true, "skipped (already has description): " + file);
            }
            // Build a YAML block scalar, each line indented two spaces under "description: |".
            StringBuilder block = new StringBuilder("description: |\n");
            for (String line : desc.split("\n", -1)) {
                block.append("  ").append(line.stripTrailing()).append("\n");
            }
            String out;
            if (src.startsWith("---")) {
                int end = src.indexOf("\n---", 3);
                if (end == -1) return new ActionResult(false, "setFrontmatterDescription: malformed frontmatter: " + file);
                out = src.substring(0, end + 1) + block + src.substring(end + 1);
            } else {
                out = "---\n" + block + "---\n" + src;
            }
            Files.writeString(file, out);
            return new ActionResult(true, "wrote description to " + file);
        } catch (Exception e) {
            return new ActionResult(false, "setFrontmatterDescription: " + e.getMessage());
        }
    }

    // ── deterministic helpers ────────────────────────────────────────────────

    /** True if the Markdown source has a frontmatter {@code description:} with a non-empty value. */
    static boolean hasFrontmatterDescription(String src) {
        if (src == null || !src.startsWith("---")) return false;
        int end = src.indexOf("\n---", 3);
        if (end == -1) return false;
        for (String line : src.substring(3, end).split("\n")) {
            if (line.startsWith("description:")) {
                return !line.substring(12).trim().isEmpty();
            }
        }
        return false;
    }

    static String detectType(Path root) {
        if (Files.exists(root.resolve("pom.xml"))) return "java-maven";
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) return "java-gradle";
        if (Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("requirements.txt"))) return "python-pip";
        if (Files.exists(root.resolve("package.json"))) return "node";
        return "unknown";
    }

    static boolean keepForType(String type, Path rel) {
        if (type.startsWith("java-")) {
            return rel.getNameCount() == 1 || rel.startsWith(Path.of("src"));
        }
        return true;
    }

    static boolean isSkipped(Path base, Path f) {
        for (Path seg : base.relativize(f)) {
            if (SKIP_DIRS.contains(seg.toString())) return true;
        }
        String name = f.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SKIP_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    /** Splits Markdown into chunks of up to {@code maxChars}, breaking only on blank-line block boundaries
     *  outside fenced code blocks, so headings/paragraphs/code blocks stay intact. */
    static List<String> chunk(String text, int maxChars) {
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
                cur.append(line).append('\n');
                continue;
            }
            if (line.isBlank() && !inFence) {
                if (cur.length() > 0) { blocks.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(line).append('\n');
            }
        }
        if (cur.length() > 0) blocks.add(cur.toString());

        List<String> chunks = new ArrayList<>();
        StringBuilder c = new StringBuilder();
        for (String b : blocks) {
            if (c.length() > 0 && c.length() + b.length() > maxChars) {
                chunks.add(c.toString().strip()); c.setLength(0);
            }
            c.append(b).append('\n');
        }
        if (c.length() > 0) chunks.add(c.toString().strip());
        return chunks;
    }
}
