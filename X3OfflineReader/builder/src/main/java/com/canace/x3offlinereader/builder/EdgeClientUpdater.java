package com.canace.x3offlinereader.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Independent, validated Edge client updates; never changes a running provider's selection. */
public final class EdgeClientUpdater {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EdgeClientUpdater() { }

    public static Path store(Path root) {
        return BuilderWorkDirectory.resolve(root).resolve("components/edge");
    }

    public static String selection(Path store) {
        try {
            String version = JSON.readTree(store.resolve("active.json").toFile()).path("version").asText();
            if (version.matches("7\\.[0-9]+\\.[0-9]+")
                    && Files.isRegularFile(store.resolve(version).resolve("edge_tts/__init__.py"))) {
                return version;
            }
        } catch (Exception ignored) {
            // An absent or damaged pointer always falls back to the bundled client.
        }
        return "bundled";
    }

    public static void environment(Map<String, String> environment, Path root) {
        Path packages = root.resolve(".tools/edge-tts/site-packages");
        environment.put("PYTHONPATH", packages.toString());
        environment.put("SSL_CERT_FILE", packages.resolve("certifi/cacert.pem").toString());
        environment.put("PYTHONUTF8", "1");
    }

    static ObjectNode run(Path root, String action) throws Exception {
        if (!action.equals("status") && !action.equals("auto") && !action.equals("force")) {
            throw new IllegalArgumentException("Unknown online component operation");
        }
        ProcessBuilder builder = new ProcessBuilder(
                root.resolve(".tools/melo/python.exe").toString(),
                root.resolve("builder/edge/updater.py").toString(), action,
                "--app", root.toString(), "--store", store(root).toString());
        environment(builder.environment(), root);
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("在线语音组件检查超时；原组件保持不变");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || output.isBlank()) {
                throw new IllegalStateException("在线语音组件检查失败；原组件保持不变");
            }
            return (ObjectNode) JSON.readTree(output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
