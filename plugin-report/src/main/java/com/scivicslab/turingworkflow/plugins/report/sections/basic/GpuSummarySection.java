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

package com.scivicslab.turingworkflow.plugins.report.sections.basic;

import com.scivicslab.turingworkflow.plugins.report.SectionBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a formatted summary of GPU hardware information extracted from workflow execution logs.
 *
 * <ul><li>{@code generate()} returns a string containing a {@code [GPU Summary]} block with per-node details (name,
 * VRAM, driver, toolkit, and architecture) and a total count of NVIDIA, AMD, and other GPUs.</li><li>{@code
 * getTitle()} returns {@code null} because the title is embedded within the generated content.</li></ul>
 *
 * <p>The section relies on a JDBC {@link java.sql.Connection} to the H2 log database and a valid session ID to be set
 * via {@link #setConnection(java.sql.Connection)} and {@link #setSessionId(long)} before calling {@code generate}.</p>
 *
 * <p>One instance may serve several workflows, provided the session ID is updated for each.</p>
 *
 * <p>If the connection is not set, the session ID is negative, or a database error occurs, {@code generate()} returns
 * an empty string.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class GpuSummarySection implements SectionBuilder {

    private static final Logger logger = Logger.getLogger(GpuSummarySection.class.getName());

    /**
     * Regular expression for parsing NVIDIA GPU details from nvidia-smi CSV output, including the model name, VRAM
     * capacity, driver version, and compute capability.
     */
    private static final Pattern NVIDIA_CSV_PATTERN = Pattern.compile(
        "^(NVIDIA [^,]+|[^,]*GeForce[^,]*|[^,]*Quadro[^,]*|[^,]*Tesla[^,]*|[^,]*A100[^,]*|[^,]*H100[^,]*|[^,]*GB[0-9]+[^,]*),\\s*(?:(\\d+)\\s*MiB|\\[N/A\\]),\\s*([\\d.]+),\\s*([\\d.]+)$"
    );

    /** Pattern for extracting GPU names from lspci output when specific vendor formats are not found. */
    private static final Pattern LSPCI_PATTERN = Pattern.compile(
        "(?:VGA compatible controller|3D controller|Display controller):\\s*(.+?)(?:\\s*\\(rev|$)");

    private Connection connection;
    private long sessionId = -1;

    /**
     * Assigns the JDBC connection used to query the H2 log database for GPU information.
     *
     * @param connection the connection to the H2 log database
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    /**
     * Sets the session ID used to query the log database.
     *
     * @param sessionId the ID of the session whose logs are to be parsed
     */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Generates a {@code [GPU Summary]} section containing a list of GPU hardware details and a summary count from the
     * workflow logs.
     *
     * <p>The result is a formatted string listing each node's GPU name, VRAM, driver version, toolkit, and
     * architecture, followed by a summary of the number of NVIDIA, AMD, and other GPUs found. If no GPU information is
     * found in the logs, or if the database connection or session ID is not properly configured, it returns an empty
     * string.</p>
     *
     * @return the formatted GPU summary text, or an empty string if no GPU information is found or if a database error
     * occurs.
     */
    @Override
    public String generate() {
        if (connection == null || sessionId < 0) {
            logger.warning("GpuSummarySection: connection or sessionId not set");
            return "";
        }

        try {
            return buildGpuSummary();
        } catch (SQLException e) {
            logger.warning("GpuSummarySection: SQL error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Constructs a formatted GPU summary string by querying the log database.
     *
     * Returns a string containing a {@code [GPU Summary]} block with per-node details (name, VRAM, driver, toolkit,
     * and architecture) and a final summary count of NVIDIA, AMD, and other GPU types, or an empty string if no GPU
     * information is found.
     *
     * @throws SQLException if a database access error occurs.
     */
    private String buildGpuSummary() throws SQLException {
        String sql = "SELECT node_id, message FROM logs " +
                     "WHERE session_id = ? AND node_id IN (" +
                     "  SELECT DISTINCT node_id FROM logs " +
                     "  WHERE session_id = ? AND message LIKE '%GPU INFO%'" +
                     ") AND (message LIKE '%GPU INFO%' OR message LIKE '%NVIDIA%' " +
                     "OR message LIKE '%GeForce%' OR message LIKE '%Quadro%' " +
                     "OR message LIKE '%CUDA_VERSION%' OR message LIKE '%Radeon%' " +
                     "OR message LIKE '%AMD_GPU%' OR message LIKE '%ROCM_VERSION%' " +
                     "OR message LIKE '%GFX_ARCH%' OR message LIKE '%GPU_NAME%' " +
                     "OR message LIKE '%AMD%' OR message LIKE '%VGA%') " +
                     "ORDER BY node_id, timestamp";

        Map<String, GpuInfo> nodeGpus = new LinkedHashMap<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            ps.setLong(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nodeId = rs.getString("node_id");
                    String message = rs.getString("message");
                    GpuInfo gpuInfo = nodeGpus.computeIfAbsent(nodeId, k -> new GpuInfo());

                    parseGpuMessage(message, gpuInfo);
                }
            }
        }

        if (nodeGpus.isEmpty()) {
            return "";  // No GPU info, skip this section
        }

        return formatOutput(nodeGpus);
    }

    /**
     * Populates the provided {@code GpuInfo} with details extracted from a log message.
     *
     * <p>Extracts GPU specifications such as model name, VRAM, driver version, toolkit, and architecture from the
     * lines of the given message. It supports NVIDIA (via {@code nvidia-smi} CSV format), AMD (via ROCm key-value
     * pairs), and {@code lspci} output. If a line does not match any known GPU data format, it is ignored.
     *
     * @param message the log message text to parse
     * @param gpuInfo the object to be updated with the extracted properties
     */
    private void parseGpuMessage(String message, GpuInfo gpuInfo) {
        for (String line : message.split("\n")) {
            String cleanLine = line.replaceFirst("^\\[node-[^\\]]+\\]\\s*", "").trim();
            if (cleanLine.contains("GPU INFO") || cleanLine.isEmpty()) continue;

            // Parse NVIDIA CUDA_VERSION line
            if (cleanLine.startsWith("CUDA_VERSION:")) {
                gpuInfo.toolkit = "CUDA " + cleanLine.replaceFirst("CUDA_VERSION:\\s*", "").trim();
                continue;
            }

            // Parse AMD ROCm output
            if (cleanLine.startsWith("AMD_GPU:")) {
                gpuInfo.isAmd = true;
                continue;
            }
            if (cleanLine.startsWith("GPU_NAME:")) {
                gpuInfo.name = cleanLine.replaceFirst("GPU_NAME:\\s*", "").trim();
                continue;
            }
            if (cleanLine.startsWith("VRAM_BYTES:")) {
                try {
                    long vramBytes = Long.parseLong(cleanLine.replaceFirst("VRAM_BYTES:\\s*", "").trim());
                    long vramGB = vramBytes / (1024L * 1024L * 1024L);
                    gpuInfo.vram = vramGB + "GB";
                } catch (NumberFormatException e) {
                    // ignore
                }
                continue;
            }
            if (cleanLine.startsWith("DRIVER_VERSION:")) {
                gpuInfo.driver = cleanLine.replaceFirst("DRIVER_VERSION:\\s*", "").trim();
                continue;
            }
            if (cleanLine.startsWith("ROCM_VERSION:")) {
                gpuInfo.toolkit = "ROCm " + cleanLine.replaceFirst("ROCM_VERSION:\\s*", "").trim();
                continue;
            }
            if (cleanLine.startsWith("GFX_ARCH:")) {
                gpuInfo.arch = cleanLine.replaceFirst("GFX_ARCH:\\s*", "").trim();
                continue;
            }

            // Parse nvidia-smi CSV output
            Matcher nvidiaMatcher = NVIDIA_CSV_PATTERN.matcher(cleanLine);
            if (nvidiaMatcher.find()) {
                gpuInfo.name = nvidiaMatcher.group(1).trim();
                String vramStr = nvidiaMatcher.group(2);
                if (vramStr != null) {
                    int vramMB = Integer.parseInt(vramStr);
                    gpuInfo.vram = (vramMB >= 1024) ? (vramMB / 1024) + "GB" : vramMB + "MB";
                }
                gpuInfo.driver = nvidiaMatcher.group(3).trim();
                gpuInfo.arch = nvidiaMatcher.group(4).trim();
                continue;
            }

            // Parse lspci output for AMD/Intel GPUs (fallback)
            Matcher lspciMatcher = LSPCI_PATTERN.matcher(cleanLine);
            if (lspciMatcher.find()) {
                String gpuName = lspciMatcher.group(1).trim();
                if (gpuInfo.name == null) {
                    gpuInfo.name = gpuName;
                }
            }
        }
    }

    /**
     * Returns a formatted string containing a {@code [GPU Summary]} block and a summary count of GPU types.
     *
     * The string contains a header, followed by lines for each node detailing its GPU name, VRAM, driver, toolkit, and
     * architecture in a CSV-like format, and ends with a summary line (e.g., "Summary: 2 NVIDIA, 1 AMD").
     */
    private String formatOutput(Map<String, GpuInfo> nodeGpus) {
        // Count GPU types
        int nvidiaCount = 0, amdCount = 0, otherCount = 0;
        for (GpuInfo gpu : nodeGpus.values()) {
            if (gpu.name != null) {
                if (gpu.name.contains("NVIDIA") || gpu.name.contains("GeForce") ||
                    gpu.name.contains("Quadro") || gpu.name.contains("Tesla")) {
                    nvidiaCount++;
                } else if (gpu.isAmd || gpu.name.contains("AMD") || gpu.name.contains("Radeon")) {
                    amdCount++;
                } else {
                    otherCount++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[GPU Summary]\n");
        for (Map.Entry<String, GpuInfo> entry : nodeGpus.entrySet()) {
            String nodeShort = entry.getKey().replaceFirst("^node-", "");
            GpuInfo gpu = entry.getValue();
            if (gpu.name != null) {
                sb.append(String.format("%s, gpu, %s%n", nodeShort, gpu.name));
            }
            if (gpu.vram != null) {
                sb.append(String.format("%s, vram, %s%n", nodeShort, gpu.vram));
            }
            if (gpu.driver != null) {
                sb.append(String.format("%s, driver, %s%n", nodeShort, gpu.driver));
            }
            if (gpu.toolkit != null) {
                sb.append(String.format("%s, toolkit, %s%n", nodeShort, gpu.toolkit));
            }
            if (gpu.arch != null) {
                sb.append(String.format("%s, arch, %s%n", nodeShort, gpu.arch));
            }
        }

        sb.append("\nSummary: ");
        boolean first = true;
        if (nvidiaCount > 0) {
            sb.append(nvidiaCount).append(" NVIDIA");
            first = false;
        }
        if (amdCount > 0) {
            if (!first) sb.append(", ");
            sb.append(amdCount).append(" AMD");
            first = false;
        }
        if (otherCount > 0) {
            if (!first) sb.append(", ");
            sb.append(otherCount).append(" Other");
        }
        sb.append("\n");

        return sb.toString();
    }

    /** Returns {@code null}. */
    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }

    /**
     * Holds GPU hardware details extracted from log messages.
     *
     * <p>Fields are populated incrementally as different log lines are parsed, since GPU information may be spread
     * across multiple log entries.</p>
     */
    private static class GpuInfo {
        /** The GPU model name (e.g., {@code "NVIDIA A100"}, {@code "AMD Radeon RX 7900"}). */
        String name;
        /** The VRAM capacity of the GPU, such as {@code "16GB"} or {@code "512MB"}. */
        String vram;
        /** The GPU driver version string. */
        String driver;
        /** The GPU toolkit version (e.g., {@code "CUDA 12.2"} or {@code "ROCm 5.7"}). */
        String toolkit;
        /** The GPU architecture (compute capability for NVIDIA, or gfx ID for AMD). */
        String arch;
        /** Indicates whether the detected GPU is an AMD device. */
        boolean isAmd;
    }
}
