package com.github.catatafishen.agentbridge.memory.embedding;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads the all-MiniLM-L6-v2 ONNX model and vocabulary on first use.
 * Files are stored globally at {@code ~/.agentbridge/models/all-MiniLM-L6-v2/}
 * so they're shared across all projects.
 *
 * <p>Downloads with a progress indicator in the IDE status bar.
 */
public final class ModelDownloader {

    private static final Logger LOG = Logger.getInstance(ModelDownloader.class);

    private static final String MODEL_DIR_NAME = "all-MiniLM-L6-v2";

    /**
     * Hugging Face ONNX model URL (exported via Optimum).
     */
    private static final String MODEL_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx";

    /**
     * Vocabulary file URL.
     */
    private static final String VOCAB_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private ModelDownloader() {}

    /**
     * Returns the model directory. Creates it if it doesn't exist.
     */
    public static @NotNull Path getModelDirectory() {
        Path home = Path.of(System.getProperty("user.home"));
        return home.resolve(".agentbridge").resolve("models").resolve(MODEL_DIR_NAME);
    }

    /**
     * Returns the path to the ONNX model file.
     */
    public static @NotNull Path getModelPath() {
        return getModelDirectory().resolve("model.onnx");
    }

    /**
     * Returns the path to the vocabulary file.
     */
    public static @NotNull Path getVocabPath() {
        return getModelDirectory().resolve("vocab.txt");
    }

    /**
     * Check if both model and vocab are already downloaded.
     */
    public static boolean isModelAvailable() {
        return Files.exists(getModelPath()) && Files.exists(getVocabPath());
    }

    /**
     * Ensure the model is downloaded. If missing, downloads with a progress indicator.
     * Blocks until the download completes or fails.
     *
     * @param project the current project (for progress display)
     * @throws IOException if the download fails
     */
    public static void ensureModelAvailable(@NotNull Project project) throws IOException {
        if (isModelAvailable()) return;

        CompletableFuture<IOException> result = new CompletableFuture<>();

        ProgressManager.getInstance().run(new Task.WithResult<Void, IOException>(
            project, "Downloading Embedding Model", true
        ) {
            @Override
            protected Void compute(@NotNull ProgressIndicator indicator) throws IOException {
                indicator.setIndeterminate(false);
                try {
                    Files.createDirectories(getModelDirectory());

                    if (!Files.exists(getVocabPath())) {
                        indicator.setText("Downloading vocabulary...");
                        indicator.setFraction(0.0);
                        downloadFile(VOCAB_URL, getVocabPath(), indicator, 0.0, 0.05);
                    }

                    if (!Files.exists(getModelPath())) {
                        indicator.setText("Downloading all-MiniLM-L6-v2 ONNX model (~90 MB)...");
                        downloadFile(MODEL_URL, getModelPath(), indicator, 0.05, 1.0);
                    }

                    indicator.setFraction(1.0);
                    indicator.setText("Model ready");
                    LOG.info("Embedding model downloaded to " + getModelDirectory());
                } catch (IOException e) {
                    cleanup();
                    throw e;
                }
                return null;
            }
        });
    }

    private static void downloadFile(
        @NotNull String url,
        @NotNull Path target,
        @NotNull ProgressIndicator indicator,
        double fractionStart,
        double fractionEnd
    ) throws IOException {
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
            }

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
            try (InputStream in = response.body()) {
                long downloaded = 0;
                byte[] buffer = new byte[65536];
                var out = Files.newOutputStream(tempFile);
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (indicator.isCanceled()) {
                        out.close();
                        Files.deleteIfExists(tempFile);
                        throw new IOException("Download cancelled by user");
                    }
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (contentLength > 0) {
                        double progress = (double) downloaded / contentLength;
                        indicator.setFraction(fractionStart + progress * (fractionEnd - fractionStart));
                    }
                }
                out.close();
            }

            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(tempFile);
            throw new IOException("Download interrupted", e);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Remove partially downloaded files on failure.
     */
    private static void cleanup() {
        try {
            Files.deleteIfExists(getModelPath());
            Files.deleteIfExists(getVocabPath());
        } catch (IOException e) {
            LOG.warn("Failed to clean up partial model download", e);
        }
    }
}
