package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.settings.ChatWebServerSettings;
import com.github.catatafishen.ideagentforcopilot.ui.ChatTheme;
import com.google.gson.Gson;
import com.intellij.ide.ui.LafManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Optional HTTP server that streams the chat panel to a local web browser (e.g. phone on LAN).
 *
 * <ul>
 *   <li>Serves {@code GET /} — PWA web app (reuses the same HTML/CSS/JS as the IDE panel)</li>
 *   <li>Serves {@code GET /events} — SSE stream of chat events</li>
 *   <li>Serves {@code GET /state} — full event log for initial page load</li>
 *   <li>Serves {@code GET /info} — project info + current model</li>
 *   <li>Serves {@code POST /prompt} — sends a prompt to the agent</li>
 *   <li>Serves {@code POST /reply} — sends a quick reply</li>
 *   <li>Serves {@code POST /nudge} — nudges the running agent</li>
 *   <li>Serves {@code POST /stop} — stops the running agent</li>
 *   <li>Serves {@code POST /permission} — responds to a permission request</li>
 *   <li>Serves {@code GET /cert.crt} — downloads the TLS certificate for device trust installation</li>
 *   <li>Serves {@code POST /cancel-nudge} — cancels a pending nudge</li>
 *   <li>Serves {@code GET /manifest.json} — PWA manifest</li>
 *   <li>Serves {@code GET /sw.js} — service worker</li>
 *   <li>Serves {@code GET /chat.css} — chat stylesheet</li>
 *   <li>Serves {@code GET /chat.bundle.js} — chat web components bundle</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class ChatWebServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(ChatWebServer.class);
    private static final int MAX_EVENTS = 600;
    private static final Gson GSON = new Gson();

    private final Project project;
    private HttpsServer httpsServer;
    private HttpServer httpServer;
    private volatile boolean running;
    private KeyStore sslKeyStore;

    // ── Event log ─────────────────────────────────────────────────────────────
    // Stored as raw JSON strings: {"seq":N,"js":"..."}
    private final List<String> eventLog = new ArrayList<>();
    private int nextSeq = 1;

    // ── SSE clients ───────────────────────────────────────────────────────────
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();

    // ── Current state (for /info) ─────────────────────────────────────────────
    private volatile String currentModel = "";
    private volatile String projectName = "";
    private volatile boolean agentRunning = false;

    // ── Action callbacks (wired by ChatToolWindowContent) ─────────────────────
    public volatile Consumer<String> onSendPrompt;
    public volatile Consumer<String> onQuickReply;
    public volatile Consumer<String> onNudge;
    public volatile Runnable onStop;
    public volatile Consumer<String> onCancelNudge;
    /**
     * Permission response: "reqId:deny" / "reqId:once" / "reqId:session"
     */
    public volatile Consumer<String> onPermissionResponse;

    public ChatWebServer(@NotNull Project project) {
        this.project = project;
        projectName = project.getName();
    }

    public static ChatWebServer getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ChatWebServer.class);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized void start() throws IOException {
        if (running) return;
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        int port = settings.getPort();

        SSLContext sslContext;
        try {
            sslContext = buildSelfSignedSslContext();
        } catch (Exception e) {
            throw new IOException("Failed to create TLS context for Chat Web Server", e);
        }

        IOException lastError = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                int httpsPort = port + attempt;
                int httpPort = httpsPort + 1;

                httpsServer = HttpsServer.create(new InetSocketAddress("0.0.0.0", httpsPort), 0);
                httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", httpPort), 0);

                if (attempt > 0) {
                    settings.setPort(httpsPort);
                }
                break;
            } catch (IOException e) {
                if (httpsServer != null) {
                    httpsServer.stop(0);
                    httpsServer = null;
                }
                if (httpServer != null) {
                    httpServer.stop(0);
                    httpServer = null;
                }
                lastError = e;
            }
        }
        if (httpsServer == null || httpServer == null)
            throw new IOException("Cannot bind Chat Web Server (HTTPS/HTTP) to any port near " + port, lastError);

        SSLContext finalSslContext = sslContext;
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(finalSslContext) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters params) {
                SSLParameters sslParams = finalSslContext.getDefaultSSLParameters();
                params.setSSLParameters(sslParams);
            }
        });

        registerContexts(httpsServer);
        registerContexts(httpServer);

        var executor = Executors.newCachedThreadPool();
        httpsServer.setExecutor(executor);
        httpServer.setExecutor(executor);

        httpsServer.start();
        httpServer.start();
        running = true;
        LOG.info("[ChatWebServer] started (HTTPS:" + httpsServer.getAddress().getPort()
            + ", HTTP:" + httpServer.getAddress().getPort() + ") for project: " + project.getBasePath());
    }

    private void registerContexts(HttpServer server) {
        server.createContext("/", this::handleRoot);
        server.createContext("/chat.css", ex -> serveClasspath(ex, "/chat/chat.css", "text/css; charset=utf-8"));
        server.createContext("/chat.bundle.js", ex -> serveClasspath(ex, "/chat/chat-components.js", "application/javascript; charset=utf-8"));
        server.createContext("/icon.svg", this::handleIconSvg);
        server.createContext("/icon-192.png", ex -> handleIconPng(ex, 192));
        server.createContext("/icon-512.png", ex -> handleIconPng(ex, 512));
        server.createContext("/manifest.json", this::handleManifest);
        server.createContext("/sw.js", this::handleServiceWorker);
        server.createContext("/cert.crt", this::handleCert);
        server.createContext("/events", this::handleSse);
        server.createContext("/state", this::handleState);
        server.createContext("/info", this::handleInfo);
        server.createContext("/prompt", ex -> handleAction(ex, body -> {
            String text = jsonString(body, "text");
            if (text != null && !text.isEmpty() && onSendPrompt != null) onSendPrompt.accept(text);
        }));
        server.createContext("/reply", ex -> handleAction(ex, body -> {
            String text = jsonString(body, "text");
            if (text != null && !text.isEmpty() && onQuickReply != null) onQuickReply.accept(text);
        }));
        server.createContext("/nudge", ex -> handleAction(ex, body -> {
            String text = jsonString(body, "text");
            if (text != null && !text.isEmpty() && onNudge != null) onNudge.accept(text);
        }));
        server.createContext("/stop", ex -> handleAction(ex, body -> {
            if (onStop != null) onStop.run();
        }));
        server.createContext("/cancel-nudge", ex -> handleAction(ex, body -> {
            String id = jsonString(body, "id");
            if (id != null && onCancelNudge != null) onCancelNudge.accept(id);
        }));
        server.createContext("/permission", ex -> handleAction(ex, body -> {
            String reqId = jsonString(body, "reqId");
            String response = jsonString(body, "response");
            if (reqId != null && response != null && onPermissionResponse != null) {
                onPermissionResponse.accept(reqId + ":" + response);
            }
        }));
    }

    public synchronized void stop() {
        if (!running) return;
        // Signal all SSE clients to close
        for (SseClient c : sseClients) c.close();
        sseClients.clear();
        if (httpsServer != null) {
            httpsServer.stop(0);
            httpsServer = null;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        running = false;
        LOG.info("[ChatWebServer] stopped for project: " + project.getBasePath());
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return httpsServer != null ? httpsServer.getAddress().getPort() : 0;
    }

    public int getHttpPort() {
        return httpServer != null ? httpServer.getAddress().getPort() : 0;
    }

    @Override
    public void dispose() {
        stop();
    }

    // ── Event pushing ─────────────────────────────────────────────────────────

    /**
     * Called from ChatConsolePanel.executeJs — mirrors every ChatController.* call to web clients.
     * Must be callable from any thread.
     */
    public void pushJsEvent(@NotNull String js) {
        if (!running) return;
        String json;
        int seq;
        boolean isClear = js.equals("ChatController.clear()");
        synchronized (this) {
            seq = nextSeq++;
            json = "{\"seq\":" + seq + ",\"js\":" + GSON.toJson(js) + "}";
            if (isClear) {
                // Don't persist clear in the log — new clients start with an empty page
                eventLog.clear();
            } else {
                eventLog.add(json);
                if (eventLog.size() > MAX_EVENTS) eventLog.remove(0);
            }
            // Track model from setCurrentModel calls
            if (js.startsWith("ChatController.setCurrentModel(")) {
                currentModel = extractFirstStringArg(js);
            }
        }
        broadcast(json);
    }

    /**
     * Pushes a transient notification event (not stored in log, only delivered to live clients).
     */
    public void pushNotification(@NotNull String title, @NotNull String body) {
        if (!running) return;
        int seq;
        synchronized (this) {
            seq = nextSeq++;
        }
        String json = "{\"seq\":" + seq + ",\"notification\":true,\"title\":"
            + GSON.toJson(title) + ",\"body\":" + GSON.toJson(body) + "}";
        broadcast(json);
    }

    public void setAgentRunning(boolean running) {
        agentRunning = running;
    }

    // ── TLS ───────────────────────────────────────────────────────────────────

    private static final String KEYSTORE_NAME = "chat-web-server.p12";
    private static final String KEYSTORE_PASSWORD = "agentbridge-ephemeral";

    private static java.nio.file.Path getKeystorePath() {
        String configPath = com.intellij.openapi.application.PathManager.getConfigPath();
        return java.nio.file.Path.of(configPath, "plugins", "intellij-copilot-plugin", KEYSTORE_NAME);
    }

    /**
     * Builds an SSLContext with a self-signed RSA certificate.
     * Stores the keystore in the plugin config directory so it persists across restarts.
     * Includes all local non-loopback IPv4 addresses in the SAN so Android and other devices
     * on the same LAN can trust the certificate after installing it via {@code /cert.crt}.
     * If the existing certificate does not cover the current LAN IPs, it is deleted and regenerated.
     */
    private SSLContext buildSelfSignedSslContext() throws Exception {
        java.nio.file.Path ksPath = getKeystorePath();
        java.io.File ksFile = ksPath.toFile();

        List<String> localIps = collectLocalIpv4Addresses();

        if (ksFile.exists() && (!certCoversAllIps(ksFile, localIps) || !certHasCaFlag(ksFile) || !certHasExpectedSubject(ksFile))) {
            LOG.info("[ChatWebServer] Regenerating certificate — local IPs changed, CA flag missing, or subject changed");
            java.nio.file.Files.delete(ksPath);
        }

        if (!ksFile.exists()) {
            java.nio.file.Path dir = ksPath.getParent();
            if (dir != null && !java.nio.file.Files.exists(dir)) {
                java.nio.file.Files.createDirectories(dir);
            }

            StringBuilder sanBuilder = new StringBuilder("dns:localhost,ip:127.0.0.1");
            for (String ip : localIps) {
                sanBuilder.append(",ip:").append(ip);
            }

            String[] cmd = {
                "keytool", "-genkeypair",
                "-alias", "agentbridge",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-keystore", ksFile.getAbsolutePath(),
                "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-keypass", KEYSTORE_PASSWORD,
                "-dname", "CN=AgentBridge Local Network, O=AgentBridge, C=FI",
                "-ext", "SAN=" + sanBuilder,
                // Required for Android (and iOS) CA trust: marks this as a CA certificate.
                // Without BasicConstraints CA:TRUE the mobile CA installer rejects it.
                "-ext", "BC:critical=ca:true"
            };

            Process process = new ProcessBuilder(cmd).start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                String error = "";
                try (java.io.InputStream is = process.getErrorStream()) {
                    error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new IOException("Failed to generate self-signed certificate via keytool: " + error);
            }
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(ksFile)) {
            ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
        }
        sslKeyStore = ks;

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static List<String> collectLocalIpv4Addresses() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return ips;
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] Could not enumerate network interfaces for SAN", e);
        }
        return ips;
    }

    private static boolean certCoversAllIps(java.io.File ksFile, List<String> requiredIps) {
        if (requiredIps.isEmpty()) return true;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(ksFile)) {
                ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
            java.security.cert.Certificate cert = ks.getCertificate("agentbridge");
            if (!(cert instanceof X509Certificate x509)) return false;
            Collection<List<?>> sans = x509.getSubjectAlternativeNames();
            if (sans == null) return false;
            Set<String> certIps = new HashSet<>();
            for (List<?> san : sans) {
                if (san.get(0) instanceof Integer type && type == 7) {
                    certIps.add((String) san.get(1));
                }
            }
            return certIps.containsAll(requiredIps);
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] Could not read existing certificate SANs", e);
            return false;
        }
    }

    /**
     * Returns {@code true} if the certificate in the keystore has BasicConstraints CA:TRUE.
     */
    private static boolean certHasCaFlag(java.io.File ksFile) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(ksFile)) {
                ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
            java.security.cert.Certificate cert = ks.getCertificate("agentbridge");
            if (!(cert instanceof X509Certificate x509)) return false;
            return x509.getBasicConstraints() >= 0; // >= 0 means CA:TRUE
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] Could not read existing certificate BasicConstraints", e);
            return false;
        }
    }

    private static final String EXPECTED_CN = "CN=AgentBridge Local Network";

    /**
     * Returns {@code true} if the certificate subject contains the expected CN.
     * Detects certs generated before the display name was set, so they are regenerated.
     */
    private static boolean certHasExpectedSubject(java.io.File ksFile) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(ksFile)) {
                ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
            java.security.cert.Certificate cert = ks.getCertificate("agentbridge");
            if (!(cert instanceof X509Certificate x509)) return false;
            return x509.getSubjectX500Principal().getName().contains(EXPECTED_CN);
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] Could not read existing certificate subject", e);
            return false;
        }
    }

    private void handleCert(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try {
            java.security.cert.Certificate cert = sslKeyStore.getCertificate("agentbridge");
            // Serve as PEM — Android's CA certificate installer requires PEM format.
            // Serving raw DER triggers Android's VPN/app-cert installer which asks for a private key.
            byte[] derBytes = cert.getEncoded();
            String pem = "-----BEGIN CERTIFICATE-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(derBytes)
                + "\n-----END CERTIFICATE-----\n";
            byte[] pemBytes = pem.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-pem-file");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"agentbridge-ca.pem\"");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, pemBytes.length);
            exchange.getResponseBody().write(pemBytes);
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] Failed to serve certificate", e);
            exchange.sendResponseHeaders(500, -1);
        }
        exchange.close();
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] html = buildWebAppHtml().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, html.length);
        exchange.getResponseBody().write(html);
        exchange.close();
    }

    private void handleManifest(HttpExchange exchange) throws IOException {
        String manifest = "{"
            + "\"name\":\"AgentBridge\","
            + "\"short_name\":\"AgentBridge\","
            + "\"id\":\"/\","
            + "\"start_url\":\"/\","
            + "\"scope\":\"/\","
            + "\"display\":\"standalone\","
            + "\"theme_color\":\"#1e1f22\","
            + "\"background_color\":\"#1e1f22\","
            + "\"icons\":["
            + "{\"src\":\"/icon-192.png\",\"sizes\":\"192x192\",\"type\":\"image/png\",\"purpose\":\"any maskable\"},"
            + "{\"src\":\"/icon-512.png\",\"sizes\":\"512x512\",\"type\":\"image/png\",\"purpose\":\"any maskable\"},"
            + "{\"src\":\"/icon.svg\",\"sizes\":\"any\",\"type\":\"image/svg+xml\"}"
            + "]}";
        sendJson(exchange, manifest);
    }

    private void handleServiceWorker(HttpExchange exchange) throws IOException {
        String sw = "self.addEventListener('install',()=>self.skipWaiting());\n"
            + "self.addEventListener('activate',e=>e.waitUntil(clients.claim()));\n"
            + "self.addEventListener('fetch',e=>e.respondWith(fetch(e.request).catch(()=>new Response('Offline',{status:503}))));\n"
            + "self.addEventListener('message',e=>{\n"
            + "  if(e.data&&e.data.type==='SHOW_NOTIFICATION'){\n"
            + "    e.waitUntil(self.registration.showNotification(e.data.title||'AgentBridge',{body:e.data.body||'',icon:'/icon-192.png',tag:'agentbridge'}));\n"
            + "  }\n"
            + "});\n";
        byte[] bytes = sw.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void handleIconSvg(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] bytes = buildIconSvg().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void handleIconPng(HttpExchange exchange, int size) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] bytes = size <= 192 ? ICON_192_PNG : ICON_512_PNG;
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ── Icon assets ───────────────────────────────────────────────────────────

    private static final byte[] ICON_192_PNG;
    private static final byte[] ICON_512_PNG;

    static {
        ICON_192_PNG = generateIconPng(192);
        ICON_512_PNG = generateIconPng(512);
    }

    private static String buildIconSvg() {
        // Web-optimised version: dark background, near-white icon elements
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\" viewBox=\"0 0 13 13\">"
            + "<rect width=\"13\" height=\"13\" rx=\"2.6\" fill=\"#1E1F22\"/>"
            + "<path d=\"M 7.925 0 L 3.907 6.5 H 6.98 L 3.907 13 L 9.58 5.318 H 6.389 Z\" fill=\"#ECEEF2\"/>"
            + "<circle cx=\"1.536\" cy=\"1.536\" r=\"1.182\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\"/>"
            + "<circle cx=\"11.464\" cy=\"1.536\" r=\"1.182\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\"/>"
            + "<circle cx=\"1.536\" cy=\"11.464\" r=\"1.182\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\"/>"
            + "<circle cx=\"11.464\" cy=\"11.464\" r=\"1.182\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\"/>"
            + "<polyline points=\"1.536,2.718 1.536,5.082 2.955,6.5\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
            + "<polyline points=\"11.464,2.718 11.464,5.082 10.045,6.5\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
            + "<polyline points=\"1.536,10.282 1.536,7.918 2.955,6.5\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
            + "<polyline points=\"11.464,10.282 11.464,7.918 10.045,6.5\" fill=\"none\" stroke=\"#ECEEF2\" stroke-width=\"0.709\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
            + "</svg>";
    }

    private static byte[] generateIconPng(int size) {
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);

        // Dark rounded-square background
        int arc = size / 5;
        g.setColor(new java.awt.Color(0x1E1F22));
        g.fillRoundRect(0, 0, size, size, arc, arc);

        // Transform into SVG coordinate space (viewBox 0 0 13 13) with padding
        double pad = size * 0.10;
        double scale = (size - 2 * pad) / 13.0;
        g.translate(pad, pad);
        g.scale(scale, scale);

        g.setColor(new java.awt.Color(0xECEEF2));

        // Lightning bolt (filled)
        java.awt.geom.Path2D.Double bolt = new java.awt.geom.Path2D.Double();
        bolt.moveTo(7.925, 0);
        bolt.lineTo(3.907, 6.5);
        bolt.lineTo(6.98, 6.5);
        bolt.lineTo(3.907, 13);
        bolt.lineTo(9.58, 5.318);
        bolt.lineTo(6.389, 5.318);
        bolt.closePath();
        g.fill(bolt);

        // Corner decorations (circles + arms, stroked)
        float sw = 0.709f;
        g.setStroke(new java.awt.BasicStroke(sw, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        double r = 1.182;
        double[][] corners = {{1.536, 1.536}, {11.464, 1.536}, {1.536, 11.464}, {11.464, 11.464}};
        for (double[] c : corners) {
            g.draw(new java.awt.geom.Ellipse2D.Double(c[0] - r, c[1] - r, r * 2, r * 2));
        }
        java.awt.geom.Path2D.Double arms = new java.awt.geom.Path2D.Double();
        arms.moveTo(1.536, 2.718);
        arms.lineTo(1.536, 5.082);
        arms.lineTo(2.955, 6.5);
        arms.moveTo(11.464, 2.718);
        arms.lineTo(11.464, 5.082);
        arms.lineTo(10.045, 6.5);
        arms.moveTo(1.536, 10.282);
        arms.lineTo(1.536, 7.918);
        arms.lineTo(2.955, 6.5);
        arms.moveTo(11.464, 10.282);
        arms.lineTo(11.464, 7.918);
        arms.lineTo(10.045, 6.5);
        g.draw(arms);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(img, "PNG", baos);
        } catch (IOException ignored) {
        }
        return baos.toByteArray();
    }

    private void handleSse(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        int fromSeq = parseFromQuery(exchange.getRequestURI().getQuery());

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);

        SseClient client = new SseClient();
        List<String> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(eventLog);
            sseClients.add(client);
        }

        try {
            OutputStream out = exchange.getResponseBody();
            // Replay buffered events newer than fromSeq
            for (String ev : snapshot) {
                if (extractSeq(ev) > fromSeq) {
                    writeSse(out, ev);
                }
            }
            // Stream live events
            while (running) {
                String ev = client.queue.poll(20, TimeUnit.SECONDS);
                if (ev == null) {
                    // Keep-alive comment
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else if (ev.equals(SseClient.CLOSE_SIGNAL)) {
                    break;
                } else {
                    writeSse(out, ev);
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // Client disconnected
        } finally {
            sseClients.remove(client);
            try {
                exchange.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void handleState(HttpExchange exchange) throws IOException {
        List<String> snapshot;
        int seq;
        synchronized (this) {
            snapshot = new ArrayList<>(eventLog);
            seq = nextSeq - 1;
        }
        StringBuilder sb = new StringBuilder("{\"seq\":").append(seq)
            .append(",\"events\":[");
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(snapshot.get(i));
        }
        sb.append("],\"info\":").append(buildInfoJson()).append("}");
        sendJson(exchange, sb.toString());
    }

    private void handleInfo(HttpExchange exchange) throws IOException {
        sendJson(exchange, buildInfoJson());
    }

    private void handleAction(HttpExchange exchange, Consumer<String> handler) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            handler.accept(body);
            exchange.sendResponseHeaders(204, -1);
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] action handler error", e);
            exchange.sendResponseHeaders(500, -1);
        }
        exchange.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcast(String json) {
        for (SseClient c : sseClients) c.offer(json);
    }

    private static void writeSse(OutputStream out, String json) throws IOException {
        byte[] bytes = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
    }

    private void serveClasspath(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String buildInfoJson() {
        return "{\"project\":" + GSON.toJson(projectName)
            + ",\"model\":" + GSON.toJson(currentModel)
            + ",\"running\":" + agentRunning + "}";
    }

    private static int parseFromQuery(@Nullable String query) {
        if (query == null) return 0;
        for (String part : query.split("&")) {
            if (part.startsWith("from=")) {
                try {
                    return Integer.parseInt(part.substring(5));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static int extractSeq(String json) {
        // Fast extraction of "seq":N from JSON string (avoids full parse)
        int idx = json.indexOf("\"seq\":");
        if (idx < 0) return 0;
        int start = idx + 6;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extracts the first single-quoted string argument from a JS call like ChatController.fn('value')
     */
    private static String extractFirstStringArg(String js) {
        int start = js.indexOf('\'');
        if (start < 0) return "";
        int end = js.indexOf('\'', start + 1);
        if (end < 0) return "";
        return js.substring(start + 1, end);
    }

    private static @Nullable String jsonString(String body, String key) {
        try {
            @SuppressWarnings("unchecked")
            var map = new Gson().fromJson(body, java.util.Map.class);
            Object v = map.get(key);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Web app HTML ──────────────────────────────────────────────────────────

    private String getAgentIconSvg(String profileId, boolean isDark) {
        String name;
        if (profileId == null) {
            name = "agentbridge";
        } else {
            switch (profileId) {
                case "anthropic":
                case "claude-cli":
                    name = "claude";
                    break;
                case "copilot":
                    name = "copilot";
                    break;
                case "opencode":
                    name = "opencode";
                    break;
                case "junie":
                    name = "junie";
                    break;
                case "kiro":
                    name = "kiro";
                    break;
                case "codex":
                    name = "codex";
                    break;
                default:
                    name = "agentbridge";
                    break;
            }
        }
        String suffix = isDark ? "_dark" : "";
        String path = "/icons/expui/" + name + suffix + ".svg";
        try (java.io.InputStream is = ChatWebServer.class.getResourceAsStream(path)) {
            if (is == null) return "";
            try (java.util.Scanner scanner = new java.util.Scanner(is, java.nio.charset.StandardCharsets.UTF_8.name())) {
                return scanner.useDelimiter("\\A").next();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String buildWebAppHtml() {
        String cssVars = ChatTheme.INSTANCE.buildCssVars();
        boolean isDark = LafManager.getInstance().getCurrentUIThemeLookAndFeel().isDark();
        String bodyClass = isDark ? "dark" : "light";

        String activeProfile = ActiveAgentManager.getInstance(project).getActiveProfileId();
        String iconSvg = getAgentIconSvg(activeProfile, isDark);
        // Ensure SVG has proper styling for the button
        if (iconSvg.contains("<svg")) {
            iconSvg = iconSvg.replace("<svg", "<svg style=\"vertical-align:text-bottom;margin-right:4px\" fill=\"currentColor\" width=\"14\" height=\"14\"");
        }

        return "<!DOCTYPE html>\n"
            + "<html lang=\"en\">\n"
            + "<head>\n"
            + "  <meta charset=\"utf-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">\n"
            + "  <title>AgentBridge \u2014 " + escHtml(projectName) + "</title>\n"
            + "  <link rel=\"manifest\" href=\"/manifest.json\">\n"
            + "  <link rel=\"stylesheet\" href=\"/chat.css\">\n"
            + "  <style>\n"
            + "    :root { " + cssVars + " }\n"
            + "  </style>\n"
            + "  <style>\n"
            + WEB_APP_CSS
            + "  </style>\n"
            + "</head>\n"
            + "<body class=\"" + bodyClass + "\">\n"
            + "  <div id=\"ab-offline\">Connection lost, reconnecting\u2026</div>\n"
            + "  <div id=\"ab-header\">\n"
            + "    <div id=\"ab-title\">AgentBridge \u2014 " + escHtml(projectName) + "</div>\n"
            + "    <div id=\"ab-model\"></div>\n"
            + "    <div id=\"ab-status\" title=\"Connecting\u2026\"></div>\n"
            + "  </div>\n"
            + "  <div id=\"ab-chat\"><chat-container></chat-container></div>\n"
            + "  <div id=\"ab-footer\">\n"
            + "    <textarea id=\"ab-input\" rows=\"1\" placeholder=\"Message\u2026\"></textarea>\n"
            + "    <button id=\"ab-send\">" + iconSvg + "<span>Send</span></button>\n"
            + "  </div>\n"
            + "  <script src=\"/chat.bundle.js\"></script>\n"
            + "  <script>\n"
            + "    const ICON_SVG = " + escJs(iconSvg) + ";\n"
            + WEB_APP_JS
            + "  </script>\n"
            + "</body>\n"
            + "</html>\n";
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escJs(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // ── Web app CSS ───────────────────────────────────────────────────────────

    private static final String WEB_APP_CSS = ""
        + "html,body{height:100%;margin:0;padding:0;background:var(--bg);color:var(--fg);}\n"
        + "body{display:flex;flex-direction:column;height:100dvh;font-family:var(--font-family);font-size:var(--font-size);}\n"
        + "#ab-offline{display:none;position:fixed;top:0;left:0;right:0;background:var(--error);color:#fff;text-align:center;padding:4px 8px;font-size:.85em;z-index:200;}\n"
        + "#ab-offline.visible{display:block;}\n"
        + "#ab-header{flex:0 0 auto;display:flex;align-items:center;gap:8px;padding:6px 10px;border-bottom:1px solid var(--fg-a16);background:var(--bg);min-height:36px;}\n"
        + "#ab-title{font-weight:600;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}\n"
        + "#ab-model{font-size:.82em;color:var(--fg-muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:180px;}\n"
        + "#ab-status{width:8px;height:8px;border-radius:50%;background:var(--fg-a16);flex:0 0 8px;transition:background .3s;}\n"
        + "#ab-status.connected{background:var(--kind-execute);}\n"
        + "#ab-status.running{background:var(--agent);animation:ab-pulse 1.5s infinite;}\n"
        + "@keyframes ab-pulse{0%,100%{opacity:1}50%{opacity:.35}}\n"
        + "#ab-chat{flex:1;overflow:hidden;position:relative;}\n"
        + "chat-container{position:absolute;inset:0;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:6px 8px;box-sizing:border-box;}\n"
        + "#ab-footer{flex:0 0 auto;border-top:1px solid var(--fg-a16);display:flex;align-items:flex-end;background:var(--bg);}\n"
        + "#ab-input{flex:1;border:0;background:var(--fg-a05);color:var(--fg);padding:6px 8px;font:inherit;resize:none;min-height:36px;max-height:120px;overflow-y:auto;outline:none;}\n"
        + "#ab-send{border:none;padding:6px 10px;cursor:pointer;font:inherit;background:var(--user-a12);color:var(--user);font-size:.88em;white-space:nowrap;height:100%;display:flex;align-items:center;}\n"
        + "#ab-send:hover{background:var(--user-a16);}\n"
        + "#ab-nudge{border:none;border-radius:var(--r-md);padding:6px 10px;cursor:pointer;font:inherit;background:var(--tool-a08);color:var(--tool);font-size:.88em;white-space:nowrap;}\n"
        + "#ab-nudge:hover{background:var(--tool-a16);}\n"
        + "#ab-send:disabled,#ab-nudge:disabled{opacity:.38;cursor:default;}\n"
        + "/* Disable tool chip clicks in web context */\n"
        + "tool-chip{pointer-events:none;}\n"
        + "tool-chip .chip-expand{display:none;}\n";

    // ── Web app JS ────────────────────────────────────────────────────────────

    private static final String WEB_APP_JS = ""
        // Bridge: replaces native Kotlin bridge with fetch-based implementations
        + "window._bridge={"
        + "openFile:()=>{},"
        + "openUrl:url=>window.open(url,'_blank'),"
        + "setCursor:c=>{document.body.style.cursor=c;},"
        + "loadMore:()=>webPost('/load-more',{}),"
        + "quickReply:text=>webPost('/reply',{text}),"
        + "permissionResponse:data=>{"
        + "  const parts=data.split(':');const resp=parts.pop();const reqId=parts.join(':');"
        + "  webPost('/permission',{reqId,response:resp});"
        + "},"
        + "openScratch:()=>{},"
        + "showToolPopup:()=>{},"
        + "cancelNudge:id=>webPost('/cancel-nudge',{id})"
        + "};\n"
        + "function webPost(path,body){return fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});}\n"
        // DOM refs
        + "const statusDot=document.getElementById('ab-status');\n"
        + "const modelEl=document.getElementById('ab-model');\n"
        + "const offlineEl=document.getElementById('ab-offline');\n"
        + "const inputEl=document.getElementById('ab-input');\n"
        + "const sendBtn=document.getElementById('ab-send');\n"
        + "const chatEl=document.querySelector('chat-container');\n"
        // Auto-scroll: track whether user is near the bottom
        + "let atBottom=true;\n"
        + "chatEl.addEventListener('scroll',()=>{"
        + "  atBottom=chatEl.scrollHeight-chatEl.scrollTop-chatEl.clientHeight<120;"
        + "},{passive:true});\n"
        + "function scrollToBottom(){chatEl.scrollTop=chatEl.scrollHeight;}\n"
        // Track agent state via ChatController overrides
        + "let agentRunning=false;\n"
        + "const _origWI=ChatController.showWorkingIndicator.bind(ChatController);\n"
        + "ChatController.showWorkingIndicator=function(){_origWI();agentRunning=true;updateButtons();};\n"
        + "const _origFT=ChatController.finalizeTurn.bind(ChatController);\n"
        + "ChatController.finalizeTurn=function(...a){_origFT(...a);agentRunning=false;updateButtons();};\n"
        + "const _origCA=ChatController.cancelAllRunning.bind(ChatController);\n"
        + "ChatController.cancelAllRunning=function(){_origCA();agentRunning=false;updateButtons();};\n"
        // Track model display
        + "const _origSCM=ChatController.setCurrentModel.bind(ChatController);\n"
        + "ChatController.setCurrentModel=function(m){_origSCM(m);modelEl.textContent=m?m.substring(m.lastIndexOf('/')+1):''};\n"
        + "function updateButtons(){"
        + "  statusDot.className=agentRunning?'running':'connected';"
        + "  sendBtn.innerHTML=ICON_SVG + '<span>' + (agentRunning?'Nudge':'Send') + '</span>';"
        + "}\n"
        + "ChatController.setClientType=(type,iconSvg)=>{"
        + "  if(iconSvg)window.ICON_SVG=iconSvg.replace('<svg','<svg style=\"vertical-align:text-bottom;margin-right:4px\" fill=\"currentColor\" width=\"14\" height=\"14\"');"
        + "  updateButtons();"
        + "};\n"
        // Info fetch
        + "fetch('/info').then(r=>r.json()).then(info=>{"
        + "  if(info.model)modelEl.textContent=info.model.substring(info.model.lastIndexOf('/')+1);"
        + "  agentRunning=info.running||false;updateButtons();"
        + "}).catch(()=>{});\n"
        // State load + SSE connect
        + "let lastSeq=0;\n"
        + "let sseRetry=null;\n"
        + "fetch('/state').then(r=>r.json()).then(st=>{"
        + "  (st.events||[]).forEach(ev=>processEvent(ev,true));"
        + "  lastSeq=st.seq||0;"
        + "  requestAnimationFrame(scrollToBottom);"
        + "  connectSSE();"
        + "}).catch(()=>connectSSE());\n"
        // Event processing
        + "function processEvent(ev,replaying){"
        + "  if(ev.notification){if(!replaying)showNotification(ev.title||'AgentBridge',ev.body||'');return;}\n"
        + "  if(ev.js){try{(0,eval)(ev.js);}catch(e){console.warn('event eval:',e,ev.js&&ev.js.substring(0,80));}}\n"
        + "  if(ev.seq>lastSeq)lastSeq=ev.seq;"
        + "  if(!replaying&&atBottom)requestAnimationFrame(scrollToBottom);"
        + "}\n"
        // SSE
        + "function connectSSE(){"
        + "  const es=new EventSource('/events?from='+lastSeq);"
        + "  es.onopen=()=>{statusDot.className=agentRunning?'running':'connected';offlineEl.classList.remove('visible');};"
        + "  es.onmessage=e=>{"
        + "    try{const ev=JSON.parse(e.data);if(!ev.seq||ev.seq>lastSeq){processEvent(ev,false);if(ev.seq)lastSeq=ev.seq;}}catch(err){}"
        + "  };"
        + "  es.onerror=()=>{"
        + "    es.close();statusDot.className='';offlineEl.classList.add('visible');"
        + "    clearTimeout(sseRetry);sseRetry=setTimeout(connectSSE,3000);"
        + "  };"
        + "}\n"
        // Notifications
        + "function showNotification(title,body){"
        + "  if(navigator.serviceWorker&&navigator.serviceWorker.controller){"
        + "    navigator.serviceWorker.controller.postMessage({type:'SHOW_NOTIFICATION',title,body});"
        + "  }else if('Notification'in window&&Notification.permission==='granted'){"
        + "    try{new Notification(title,{body,icon:'/icon.svg',tag:'ab'});}catch(e){}"
        + "  }"
        + "}\n"
        + "function reqNotifPerm(){if('Notification'in window&&Notification.permission==='default')Notification.requestPermission();}\n"
        + "document.addEventListener('click',reqNotifPerm,{once:true});\n"
        // Quick-reply bridge (ask_user responses)
        + "document.addEventListener('quick-reply',e=>window._bridge.quickReply(e.detail.text));\n"
        // Input auto-resize
        + "inputEl.addEventListener('input',()=>{"
        + "  inputEl.style.height='auto';"
        + "  inputEl.style.height=Math.min(inputEl.scrollHeight,120)+'px';"
        + "});\n"
        + "inputEl.addEventListener('keydown',e=>{"
        + "  if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();sendAction();}"
        + "});\n"
        // Send/nudge actions
        + "sendBtn.onclick=sendAction;\n"
        + "function sendAction(){"
        + "  const t=inputEl.value.trim();if(!t)return;"
        + "  inputEl.value='';inputEl.style.height='auto';"
        + "  webPost(agentRunning?'/nudge':'/prompt',{text:t});"
        + "}\n"
        // PWA service worker
        + "if('serviceWorker'in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});\n";

    // ── SSE client ────────────────────────────────────────────────────────────

    private static final class SseClient {
        static final String CLOSE_SIGNAL = "__close__";
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(300);

        boolean offer(String data) {
            return queue.offer(data);
        }

        void close() {
            queue.offer(CLOSE_SIGNAL);
        }
    }
}
