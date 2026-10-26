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

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
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
 * A set of deterministic actors for code-understanding and document-generation workflows that scan projects, split
 * documents, and persist text to files.
 *
 * <ul>
 * <li>{@code chunkFile} — splits a Markdown file into chunks of up to {@code maxChars} characters and appends them to
 * a named list actor. Returns the number of chunks created.</li>
 * <li>{@code writeFileFromList} — joins the items in a named list actor using a separator and writes the result to a
 * file. Returns the output path.</li>
 * <li>{@code scanProject} — enumerates source files in a directory, skipping build and VCS noise, and appends each
 * file's path and content to a named list actor. Returns the number of files found.</li>
 * <li>{@code joinListToStr} — joins the items in a named list actor using a separator and stores the resulting string
 * in a named string actor. Returns the number of items joined.</li>
 * <li>{@code writeStrToFile} — writes the content of a named string actor to a file. Returns the output path.</li>
 * <li>{@code listMarkdownNoDescription} — finds Markdown files in a directory that lack a frontmatter description and
 * adds their paths to a named list actor. Returns the number of files found.</li>
 * <li>{@code readFileToStr} — reads a file and stores its entire content in a named string actor. Returns the path of
 * the file read.</li>
 * <li>{@code setFrontmatterDescription} — inserts a description from a named string actor into the YAML frontmatter of
 * a Markdown file. Returns a message indicating if the file was updated or skipped.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows at once, as actions are executed one at a time per actor.</p>
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

    // ------------------------------------------------------------------
    // Argument shapes
    //
    // Every action used to take a positional JSON array. Two of them take two actor names in a
    // row, where swapping the pair passes deserialisation and writes to the wrong place. Under a
    // record the same mistake is a key that does not match a component.
    // ------------------------------------------------------------------

    /**
     * Arguments for splitting a file into Markdown-aware chunks.
     *
     * @param file the path of the file to read
     * @param listActor the name of the list actor where the chunks are added
     * @param maxChars the largest number of characters allowed in a single chunk; omit to use the default of 3,000
     */
    public record ChunkFileArgs(@NotNull String file, @NotNull String listActor, Integer maxChars) {}

    /**
     * The path of the file to write, the name of the list actor providing the items, and the text to place between
     * them.
     *
     * @param file path of the file to write
     * @param listActor name of the list actor the pieces come from
     * @param separator text placed between pieces; omit to use a blank line
     */
    public record WriteFileFromListArgs(@NotNull String file, @NotNull String listActor, String separator) {}

    /**
     * Arguments for joining a list of items into a single string.
     *
     * @param listActor name of the list actor the pieces come from
     * @param strActor name of the text actor the joined result is stored in
     * @param separator text placed between pieces; omit to use a blank line
     */
    public record JoinListToStrArgs(@NotNull String listActor, @NotNull String strActor, String separator) {}

    /**
     * The path of the file to read and the name of the text actor where the contents are stored.
     *
     * @param file the path of the file to read
     * @param strActor the name of the text actor where the contents are stored
     */
    public record ReadFileToStrArgs(@NotNull String file, @NotNull String strActor) {}

    /**
     * Arguments for inserting a description into a Markdown file's YAML frontmatter.
     *
     * @param file path of the Markdown file to edit
     * @param strActor name of the text actor holding the description
     */
    public record SetFrontmatterDescriptionArgs(@NotNull String file, @NotNull String strActor) {}

    /**
     * The directory to search and the name of the list actor where found paths are added.
     *
     * @param directory the directory to search under
     * @param listActor the name of the list actor the paths are added to
     */
    public record ListMarkdownNoDescriptionArgs(@NotNull String directory, @NotNull String listActor) {}

    /**
     * Arguments for scanning a project directory and populating a list with file contents.
     *
     * @param directory the project root directory to scan
     * @param listActor the name of the list actor where findings are appended
     * @param maxChars the maximum number of characters to read from each file; omit to use the default
     */
    public record ScanProjectArgs(@NotNull String directory, @NotNull String listActor, Integer maxChars) {}

    /**
     * The path of the file to write and the name of the text actor supplying the contents.
     *
     * @param file path of the file to write
     * @param strActor name of the text actor supplying the contents
     */
    public record WriteStrToFileArgs(@NotNull String file, @NotNull String strActor) {}

    @Action(value = "chunkFile", argsType = ChunkFileArgs.class)
    public ActionResult chunkFile(ChunkFileArgs args) {
        try {
            Path in = Path.of(args.file());
            String listName = args.listActor();
            int max = args.maxChars() == null ? DEFAULT_CHUNK_CHARS : args.maxChars();
            List<String> chunks = chunk(Files.readString(in), max);
            IIActorRef<?> list = system.getIIActor(listName);
            if (list == null) return new ActionResult(false, "chunkFile: list not found: " + listName);
            for (String c : chunks) list.callByActionName("add", c);
            return new ActionResult(true, String.valueOf(chunks.size()));
        } catch (Exception e) {
            return new ActionResult(false, "chunkFile: " + e.getMessage());
        }
    }

    @Action(value = "writeFileFromList", argsType = WriteFileFromListArgs.class)
    public ActionResult writeFileFromList(WriteFileFromListArgs args) {
        try {
            Path out = Path.of(args.file());
            String listName = args.listActor();
            String sep = args.separator() == null ? "\n\n" : args.separator();
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

    @Action(value = "joinListToStr", argsType = JoinListToStrArgs.class)
    public ActionResult joinListToStr(JoinListToStrArgs args) {
        try {
            String listName = args.listActor();
            String strName = args.strActor();
            String sep = args.separator() == null ? "\n\n" : args.separator();
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

    @Action(value = "writeStrToFile", argsType = WriteStrToFileArgs.class)
    public ActionResult writeStrToFile(WriteStrToFileArgs args) {
        try {
            Path out = Path.of(args.file());
            String strName = args.strActor();
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

    @Action(value = "scanProject", argsType = ScanProjectArgs.class)
    public ActionResult scanProject(ScanProjectArgs args) {
        try {
            Path base = Path.of(args.directory()).toAbsolutePath().normalize();
            String listName = args.listActor();
            int maxChars = args.maxChars() == null ? DEFAULT_FILE_CHARS : args.maxChars();
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

    @Action(value = "listMarkdownNoDescription", argsType = ListMarkdownNoDescriptionArgs.class)
    public ActionResult listMarkdownNoDescription(ListMarkdownNoDescriptionArgs args) {
        try {
            Path base = Path.of(args.directory()).toAbsolutePath().normalize();
            String listName = args.listActor();
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

    @Action(value = "readFileToStr", argsType = ReadFileToStrArgs.class)
    public ActionResult readFileToStr(ReadFileToStrArgs args) {
        try {
            Path in = Path.of(args.file());
            String strName = args.strActor();
            IIActorRef<?> str = system.getIIActor(strName);
            if (str == null) return new ActionResult(false, "readFileToStr: str actor not found: " + strName);
            str.callByActionName("set", Files.readString(in));
            return new ActionResult(true, in.toString());
        } catch (Exception e) {
            return new ActionResult(false, "readFileToStr: " + e.getMessage());
        }
    }

    @Action(value = "setFrontmatterDescription", argsType = SetFrontmatterDescriptionArgs.class)
    public ActionResult setFrontmatterDescription(SetFrontmatterDescriptionArgs args) {
        try {
            Path file = Path.of(args.file());
            String strName = args.strActor();
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

    /**
     * Returns true if the Markdown content contains a YAML frontmatter block with a non-empty {@code description:}
     * field.
     *
     * @param src the Markdown content to check
     * @return true if a non-empty description is found within the frontmatter, false otherwise
     */
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

    /**
     * Returns a list of strings that are segments of the input text, where each segment is at most {@code maxChars}
     * characters long.
     *
     * <p>The segments are formed by grouping blocks of text separated by blank lines, ensuring that no single segment
     * exceeds the character limit. Breaks occur at blank lines that are not contained within fenced code blocks.</p>
     *
     * @param text the Markdown content to split
     * @param maxChars the maximum number of characters allowed in a single chunk
     */
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
