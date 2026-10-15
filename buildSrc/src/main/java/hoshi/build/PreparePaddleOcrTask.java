package hoshi.build;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

/**
 * Build-time only: downloads pinned PP-OCRv6 ONNX weights plus the recognition
 * character dictionary source and stages them as generated APK assets under
 * {@code assetPath}. Every artifact is verified by exact byte size and SHA-256
 * before publication, so the bundled models always match the pinned release.
 */
public abstract class PreparePaddleOcrTask extends DefaultTask {
    @Input public abstract Property<String> getDetUrl();
    @Input public abstract Property<String> getDetSha256();
    @Input public abstract Property<Long> getDetBytes();
    @Input public abstract Property<String> getRecUrl();
    @Input public abstract Property<String> getRecSha256();
    @Input public abstract Property<Long> getRecBytes();
    @Input public abstract Property<String> getRecConfigUrl();
    @Input public abstract Property<String> getRecConfigSha256();
    @Input public abstract Property<Long> getRecConfigBytes();
    /** Asset directory holding {@code det/inference.onnx} and {@code rec/}. */
    @Input public abstract Property<String> getAssetPath();
    /** Recognition dictionary source name inside {@code rec/}. */
    @Input public abstract Property<String> getRecConfigFileName();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Internal public abstract DirectoryProperty getAssetsDirectory();

    private static String sha(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(60_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent", "Hoshi-Reader-Android-build");
        int status = connection.getResponseCode();
        int redirects = 0;
        while ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                && redirects < 8) {
            String location = connection.getHeaderField("Location");
            if (location == null) break;
            connection.disconnect();
            connection = (HttpURLConnection) URI.create(location).toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(60_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("User-Agent", "Hoshi-Reader-Android-build");
            status = connection.getResponseCode();
            redirects++;
        }
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Model download failed with HTTP " + status + ": " + url);
        }
        try (InputStream input = connection.getInputStream()) {
            return input.readAllBytes();
        } finally {
            connection.disconnect();
        }
    }

    private void fetch(File target, String url, String sha256, long bytes) throws Exception {
        if (target.isFile() && target.length() == bytes
                && sha(Files.readAllBytes(target.toPath())).equalsIgnoreCase(sha256)) {
            return;
        }
        byte[] data = download(url);
        if (data.length != bytes) {
            throw new IOException("Model size mismatch for " + url
                    + ": expected " + bytes + " but got " + data.length);
        }
        if (!sha(data).equalsIgnoreCase(sha256)) {
            throw new IOException("Model SHA-256 mismatch for " + url);
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.write(tmp.toPath(), data);
        if (!tmp.renameTo(target)) {
            throw new IOException("Could not publish " + target);
        }
    }

    @TaskAction public void prepare() throws Exception {
        File root = getOutputDirectory().get().getAsFile();
        File assets = new File(root, "assets");
        File modelDir = new File(assets, getAssetPath().get());
        File detDir = new File(modelDir, "det");
        File recDir = new File(modelDir, "rec");
        Files.createDirectories(detDir.toPath());
        Files.createDirectories(recDir.toPath());
        fetch(new File(detDir, "inference.onnx"),
                getDetUrl().get(), getDetSha256().get(), getDetBytes().get());
        fetch(new File(recDir, "inference.onnx"),
                getRecUrl().get(), getRecSha256().get(), getRecBytes().get());
        fetch(new File(recDir, getRecConfigFileName().get()),
                getRecConfigUrl().get(), getRecConfigSha256().get(), getRecConfigBytes().get());
    }
}
