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
 * <p>A list an action finds or makes is answered as a JSON array in its result, which the workflow keeps with
 * {@code this.putJson {path: chunks, value: "jexl: result"}}. A list or a text an action uses is passed to it as a
 * value, usually read from the workflow's state ({@code items: "jexl: state.select('chunks')"}).</p>
 *
 * <ul>
 * <li>{@code chunkFile} — splits a Markdown file into chunks of up to {@code maxChars} characters. Answers the chunks
 * as a JSON array.</li>
 * <li>{@code scanProject} — enumerates source files in a directory, skipping build and VCS noise. Answers each file's
 * path and content as a JSON array.</li>
 * <li>{@code listMarkdownNoDescription} — finds Markdown files in a directory that lack a frontmatter description.
 * Answers their paths as a JSON array.</li>
 * <li>{@code writeFileFromList} — joins {@code items} using a separator and writes the result to a file. Answers the
 * output path.</li>
 * <li>{@code joinListToStr} — joins {@code items} using a separator. Answers the joined text.</li>
 * <li>{@code readFileToStr} — reads a file. Answers its entire content.</li>
 * <li>{@code writeStrToFile} — writes {@code content} to a file. Answers the output path.</li>
 * <li>{@code setFrontmatterDescription} — inserts {@code description} into the YAML frontmatter of a Markdown file.
 * Answers a message saying whether the file was updated or skipped.</li>
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

    public CodeDocActor(String name, IIActorSystem system) {
        super(name, new Object(), system);
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
     * @param maxChars the largest number of characters allowed in a single chunk; omit to use the default of 3,000
     */
    public record ChunkFileArgs(@NotNull String file, Integer maxChars) {}

    /**
     * The path of the file to write, the items to join into it, and the text to place between them.
     *
     * @param file path of the file to write
     * @param items the pieces, in order; usually {@code "jexl: state.select('chunks')"}
     * @param separator text placed between pieces; omit to use a blank line
     */
    public record WriteFileFromListArgs(@NotNull String file, @NotNull List<String> items, String separator) {}

    /**
     * Arguments for joining items into a single text.
     *
     * @param items the pieces, in order; usually {@code "jexl: state.select('chunks')"}
     * @param separator text placed between pieces; omit to use a blank line
     */
    public record JoinListToStrArgs(@NotNull List<String> items, String separator) {}

    /**
     * The path of the file to read.
     *
     * @param file the path of the file to read
     */
    public record ReadFileToStrArgs(@NotNull String file) {}

    /**
     * Arguments for inserting a description into a Markdown file's YAML frontmatter.
     *
     * @param file path of the Markdown file to edit
     * @param description the description to insert; usually {@code "jexl: state.getString('description')"}
     */
    public record SetFrontmatterDescriptionArgs(@NotNull String file, @NotNull String description) {}

    /**
     * The directory to search for Markdown files without a description.
     *
     * @param directory the directory to search under
     */
    public record ListMarkdownNoDescriptionArgs(@NotNull String directory) {}

    /**
     * Arguments for scanning a project directory.
     *
     * @param directory the project root directory to scan
     * @param maxChars the maximum number of characters to read from each file; omit to use the default
     */
    public record ScanProjectArgs(@NotNull String directory, Integer maxChars) {}

    /**
     * The path of the file to write and the text to write to it.
     *
     * @param file path of the file to write
     * @param content the text to write; usually {@code "jexl: state.getString('text')"}
     */
    public record WriteStrToFileArgs(@NotNull String file, @NotNull String content) {}

    /** A list as the result of an action: a JSON array of the items, in order. */
    private static ActionResult listResult(List<String> items) {
        return new ActionResult(true, new JSONArray(items).toString());
    }

    @Action(value = "chunkFile", argsType = ChunkFileArgs.class)
    public ActionResult chunkFile(ChunkFileArgs args) {
        try {
            Path in = Path.of(args.file());
            int max = args.maxChars() == null ? DEFAULT_CHUNK_CHARS : args.maxChars();
            return listResult(chunk(Files.readString(in), max));
        } catch (Exception e) {
            return new ActionResult(false, "chunkFile: " + e.getMessage());
        }
    }

    @Action(value = "writeFileFromList", argsType = WriteFileFromListArgs.class)
    public ActionResult writeFileFromList(WriteFileFromListArgs args) {
        try {
            Path out = Path.of(args.file());
            String sep = args.separator() == null ? "\n\n" : args.separator();
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, String.join(sep, args.items()));
            return new ActionResult(true, out.toString());
        } catch (Exception e) {
            return new ActionResult(false, "writeFileFromList: " + e.getMessage());
        }
    }

    @Action(value = "joinListToStr", argsType = JoinListToStrArgs.class)
    public ActionResult joinListToStr(JoinListToStrArgs args) {
        String sep = args.separator() == null ? "\n\n" : args.separator();
        return new ActionResult(true, String.join(sep, args.items()));
    }

    @Action(value = "writeStrToFile", argsType = WriteStrToFileArgs.class)
    public ActionResult writeStrToFile(WriteStrToFileArgs args) {
        try {
            Path out = Path.of(args.file());
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, args.content());
            return new ActionResult(true, out.toString());
        } catch (Exception e) {
            return new ActionResult(false, "writeStrToFile: " + e.getMessage());
        }
    }

    @Action(value = "scanProject", argsType = ScanProjectArgs.class)
    public ActionResult scanProject(ScanProjectArgs args) {
        try {
            Path base = Path.of(args.directory()).toAbsolutePath().normalize();
            int maxChars = args.maxChars() == null ? DEFAULT_FILE_CHARS : args.maxChars();
            String type = detectType(base);
            List<String> found = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> !isSkipped(base, f))
                    .filter(f -> keepForType(type, base.relativize(f)))
                    .sorted()
                    .forEach(f -> {
                        String c;
                        try {
                            if (Files.size(f) > 1_000_000) return;
                            c = Files.readString(f);
                        } catch (Exception ignore) {
                            return; // skip unreadable
                        }
                        if (c.length() > maxChars) c = c.substring(0, maxChars) + "\n…(truncated)…";
                        found.add("FILE: " + base.relativize(f) + "\n\n" + c);
                    });
            }
            return listResult(found);
        } catch (Exception e) {
            return new ActionResult(false, "scanProject: " + e.getMessage());
        }
    }

    @Action(value = "listMarkdownNoDescription", argsType = ListMarkdownNoDescriptionArgs.class)
    public ActionResult listMarkdownNoDescription(ListMarkdownNoDescriptionArgs args) {
        try {
            Path base = Path.of(args.directory()).toAbsolutePath().normalize();
            List<String> found = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .filter(f -> !isSkipped(base, f))
                    .sorted()
                    .forEach(f -> {
                        boolean described;
                        try {
                            described = hasFrontmatterDescription(Files.readString(f));
                        } catch (Exception ignore) {
                            return; // skip unreadable
                        }
                        if (!described) {
                            found.add(f.toString());
                        }
                    });
            }
            return listResult(found);
        } catch (Exception e) {
            return new ActionResult(false, "listMarkdownNoDescription: " + e.getMessage());
        }
    }

    @Action(value = "readFileToStr", argsType = ReadFileToStrArgs.class)
    public ActionResult readFileToStr(ReadFileToStrArgs args) {
        try {
            return new ActionResult(true, Files.readString(Path.of(args.file())));
        } catch (Exception e) {
            return new ActionResult(false, "readFileToStr: " + e.getMessage());
        }
    }

    @Action(value = "setFrontmatterDescription", argsType = SetFrontmatterDescriptionArgs.class)
    public ActionResult setFrontmatterDescription(SetFrontmatterDescriptionArgs args) {
        try {
            Path file = Path.of(args.file());
            String desc = args.description().strip();
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
