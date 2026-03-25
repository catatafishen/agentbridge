package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.BuildInfo;
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
    /**
     * PEM-encoded CA certificate served at {@code /cert.crt} for device installation.
     */
    private volatile byte[] caCertPemBytes;

    // ── Event log ─────────────────────────────────────────────────────────────
    // Stored as raw JSON strings: {"seq":N,"js":"..."}
    private final List<String> eventLog = new ArrayList<>();
    private int nextSeq = 1;

    // ── SSE clients ───────────────────────────────────────────────────────────
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();

    // ── Web Push ──────────────────────────────────────────────────────────────
    private volatile WebPushSender webPush;

    // ── Current state (for /info) ─────────────────────────────────────────────
    private volatile String currentModel = "";
    private volatile String projectName = "";
    private volatile boolean agentRunning = false;
    private volatile boolean connected = false;
    private volatile String modelsJson = "[]";
    private volatile String profilesJson = "[]";

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
    public volatile Runnable onDisconnect;
    public volatile Consumer<String> onConnect;
    public volatile Consumer<String> onSelectModel;

    public ChatWebServer(@NotNull Project project) {
        this.project = project;
        projectName = project.getName();
    }

    public static ChatWebServer getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ChatWebServer.class);
    }

    // ── State setters (called by ChatToolWindowContent) ───────────────────────

    public void setConnected(boolean value) {
        this.connected = value;
    }

    public void setModelsJson(String json) {
        this.modelsJson = json != null ? json : "[]";
    }

    public void setProfilesJson(String json) {
        this.profilesJson = json != null ? json : "[]";
    }

    /**
     * Populates profilesJson with all available agent profiles from the IDE.
     */
    public void refreshAvailableProfiles() {
        try {
            java.util.List<AgentProfile> profiles = AgentProfileManager.getInstance().getAllProfiles();
            java.util.List<java.util.Map<String, String>> profileList = new java.util.ArrayList<>();
            for (AgentProfile p : profiles) {
                var m = new java.util.LinkedHashMap<String, String>();
                m.put("id", p.getId());
                m.put("name", p.getDisplayName());
                profileList.add(m);
            }
            this.profilesJson = GSON.toJson(profileList);
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] Failed to refresh profiles: " + e.getMessage());
            this.profilesJson = "[]";
        }
    }

    /**
     * Sends a transient JS-eval event to all connected SSE clients (not stored in event log).
     */
    public void broadcastTransient(String js) {
        String event = "{\"js\":" + GSON.toJson(js) + "}";
        for (SseClient c : sseClients) c.offer(event);
    }

    // ── Web Push helpers ──────────────────────────────────────────────────────

    /**
     * Returns (creating if needed) the {@link WebPushSender}, or {@code null} if key gen fails.
     */
    private @Nullable WebPushSender getOrCreateWebPush() {
        if (webPush != null) return webPush;
        synchronized (this) {
            if (webPush != null) return webPush;
            try {
                ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
                java.security.KeyPair kp = WebPushSender.deserializeKeyPair(
                    settings.getVapidPrivateKey(), settings.getVapidPublicKey());
                if (kp == null) {
                    kp = WebPushSender.generateVapidKeyPair();
                    String[] serialized = WebPushSender.serializeKeyPair(kp);
                    settings.setVapidPrivateKey(serialized[0]);
                    settings.setVapidPublicKey(serialized[1]);
                    LOG.info("[ChatWebServer] Generated new VAPID key pair");
                }
                webPush = new WebPushSender(kp);
            } catch (Exception e) {
                LOG.warn("[ChatWebServer] Failed to initialise WebPushSender: " + e.getMessage());
                return null;
            }
        }
        return webPush;
    }

    /**
     * Parses a Web Push subscription JSON into a {@link WebPushSender.PushSubscription}.
     */
    private static @Nullable WebPushSender.PushSubscription parseSubscription(@NotNull String json) {
        String endpoint = jsonString(json, "endpoint");
        int keysIdx = json.indexOf("\"keys\"");
        if (endpoint == null || keysIdx < 0) return null;
        String keysBlock = json.substring(keysIdx);
        String p256dh = jsonString(keysBlock, "p256dh");
        String auth = jsonString(keysBlock, "auth");
        if (p256dh == null || auth == null) return null;
        return new WebPushSender.PushSubscription(endpoint, p256dh, auth);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized void start() throws IOException {
        if (running) return;
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        int port = settings.getPort();
        boolean https = settings.isHttpsEnabled();

        SSLContext sslContext = null;
        if (https) {
            try {
                sslContext = buildSslContext();
            } catch (Exception e) {
                throw new IOException("Failed to create TLS context for Chat Web Server", e);
            }
        }

        var executor = Executors.newCachedThreadPool();

        IOException lastError = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            int tryPort = port + attempt;
            try {
                if (https) {
                    httpsServer = HttpsServer.create(new InetSocketAddress("0.0.0.0", tryPort), 0);
                }
                httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", tryPort + (https ? 1 : 0)), 0);
                if (attempt > 0) settings.setPort(tryPort);
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

        boolean bound = https ? (httpsServer != null && httpServer != null) : httpServer != null;
        if (!bound) throw new IOException("Cannot bind Chat Web Server to any port near " + port, lastError);

        httpServer.setExecutor(executor);
        if (https) {
            registerContexts(httpsServer);
            SSLContext finalSslContext = sslContext;
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(finalSslContext) {
                @Override
                public void configure(com.sun.net.httpserver.HttpsParameters params) {
                    SSLParameters sslParams = finalSslContext.getDefaultSSLParameters();
                    params.setSSLParameters(sslParams);
                }
            });
            httpsServer.setExecutor(executor);
            httpsServer.start();

            registerCertOnlyContext(httpServer);
            httpServer.start();
            LOG.info("[ChatWebServer] started (HTTPS:" + httpsServer.getAddress().getPort()
                + " + cert-HTTP:" + httpServer.getAddress().getPort()
                + ") for project: " + project.getBasePath());
        } else {
            registerContexts(httpServer);
            httpServer.start();
            LOG.info("[ChatWebServer] started (HTTP:" + httpServer.getAddress().getPort()
                + ") for project: " + project.getBasePath());
        }

        // Populate available profiles for the connect page
        refreshAvailableProfiles();
        running = true;
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
        server.createContext("/push-subscribe", ex -> handleAction(ex, body -> {
            WebPushSender wp = getOrCreateWebPush();
            if (wp == null) return;
            WebPushSender.PushSubscription sub = parseSubscription(body);
            if (sub != null) wp.addSubscription(sub);
        }));
        server.createContext("/push-unsubscribe", ex -> handleAction(ex, body -> {
            String endpoint = jsonString(body, "endpoint");
            WebPushSender wp = webPush;
            if (endpoint != null && wp != null) wp.removeSubscription(endpoint);
        }));
        server.createContext("/disconnect", ex -> handleAction(ex, body -> {
            if (onDisconnect != null) onDisconnect.run();
        }));
        server.createContext("/connect", ex -> handleAction(ex, body -> {
            String profileId = jsonString(body, "profileId");
            if (profileId != null && !profileId.isEmpty() && onConnect != null) onConnect.accept(profileId);
        }));
        server.createContext("/set-model", ex -> handleAction(ex, body -> {
            String modelId = jsonString(body, "modelId");
            if (modelId != null && !modelId.isEmpty() && onSelectModel != null) onSelectModel.accept(modelId);
        }));
    }

    private void registerCertOnlyContext(HttpServer server) {
        server.createContext("/cert.crt", this::handleCert);
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
        if (httpsServer != null) return httpsServer.getAddress().getPort();
        if (httpServer != null) return httpServer.getAddress().getPort();
        return 0;
    }

    /**
     * Returns the port where the HTTP server is listening (for cert downloads).
     * When HTTPS is enabled this is always a separate port from the main server.
     */
    public int getHttpCertPort() {
        if (httpServer != null) return httpServer.getAddress().getPort();
        return getPort();
    }

    /**
     * Returns {@code true} if the running server is HTTPS, {@code false} if HTTP.
     */
    public boolean isHttps() {
        return httpsServer != null;
    }

    /**
     * Returns the primary LAN IPv4 address, or {@code null} if none is found.
     */
    public static @org.jetbrains.annotations.Nullable String getLanIp() {
        List<String> ips = collectLocalIpv4Addresses();
        return ips.isEmpty() ? null : ips.get(0);
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
     * Pushes a notification to live SSE clients and, if any Web Push subscriptions are registered,
     * sends a Web Push to devices that may have the browser closed.
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
        // Also send via Web Push for devices with the browser closed
        WebPushSender wp = webPush; // read volatile once; null if not yet initialised
        if (wp != null) {
            if (wp.hasSubscriptions()) {
                String payload = "{\"seq\":" + seq + ",\"title\":" + GSON.toJson(title) + "}";
                wp.sendToAll(payload);
            } else {
                LOG.debug("[Chat] Web Push configured but no subscriptions registered for: " + title);
            }
        } else {
            LOG.debug("[Chat] Web Push not initialized yet for: " + title);
        }
    }

    public void setAgentRunning(boolean running) {
        agentRunning = running;
    }

    // ── TLS ───────────────────────────────────────────────────────────────────

    private static final String KEYSTORE_PASSWORD = "agentbridge-ephemeral";

    // Must match the -dname used in generateCaPlusServerCerts. Update both together.
    private static final String EXPECTED_SERVER_SUBJECT_CN = "CN=AgentBridge Server";
    private static final String EXPECTED_SERVER_SUBJECT_O = "O=AgentBridge";

    private static java.nio.file.Path getPluginDir() {
        String configPath = com.intellij.openapi.application.PathManager.getConfigPath();
        return java.nio.file.Path.of(configPath, "plugins", "intellij-copilot-plugin");
    }

    /**
     * Builds an SSLContext backed by a proper CA + server certificate chain.
     *
     * <ul>
     *   <li>{@code ca.p12} — CA key pair + self-signed CA cert (CA:TRUE, long-lived).
     *       The device installs this via {@code /cert.crt}.</li>
     *   <li>{@code server.p12} — Server key pair + CA-signed server cert (CA:FALSE, serverAuth EKU,
     *       SANs, short-lived). Presented during the HTTPS handshake.</li>
     * </ul>
     * <p>
     * Certificates are regenerated when the server cert's SANs don't match the current LAN IPs
     * or when the expected subject/SAN is missing (e.g. first run or upgrade from old format).
     */
    private SSLContext buildSslContext() throws Exception {
        java.nio.file.Path pluginDir = getPluginDir();
        java.io.File caKsFile = pluginDir.resolve("ca.p12").toFile();
        java.io.File serverKsFile = pluginDir.resolve("server.p12").toFile();

        List<String> localIps = collectLocalIpv4Addresses();

        boolean needsRegen = !caKsFile.exists()
            || !serverKsFile.exists()
            || !serverCertCoversAllIps(serverKsFile, localIps)
            || !serverCertHasExpectedSubject(serverKsFile);

        if (needsRegen) {
            LOG.info("[ChatWebServer] Generating CA + server certificates");
            java.nio.file.Files.deleteIfExists(caKsFile.toPath());
            java.nio.file.Files.deleteIfExists(serverKsFile.toPath());
            java.nio.file.Files.createDirectories(pluginDir);
            generateCaPlusServerCerts(pluginDir, caKsFile, serverKsFile, localIps);
        }

        // Load CA cert for device installation at /cert.crt
        KeyStore caKs = KeyStore.getInstance("PKCS12");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(caKsFile)) {
            caKs.load(fis, KEYSTORE_PASSWORD.toCharArray());
        }
        byte[] derBytes = caKs.getCertificate("ca").getEncoded();
        String pem = "-----BEGIN CERTIFICATE-----\n"
            + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(derBytes)
            + "\n-----END CERTIFICATE-----\n";
        caCertPemBytes = pem.getBytes(StandardCharsets.UTF_8);

        // Load server keystore for the HTTPS handshake
        KeyStore serverKs = KeyStore.getInstance("PKCS12");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(serverKsFile)) {
            serverKs.load(fis, KEYSTORE_PASSWORD.toCharArray());
        }
        sslKeyStore = serverKs;

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverKs, KEYSTORE_PASSWORD.toCharArray());

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /**
     * Generates a CA key pair + self-signed CA cert, then a server key pair signed by the CA.
     * Uses keytool for all crypto operations (no external library required).
     */
    private static void generateCaPlusServerCerts(
        java.nio.file.Path pluginDir,
        java.io.File caKsFile,
        java.io.File serverKsFile,
        List<String> localIps) throws Exception {

        java.io.File caExportFile = pluginDir.resolve("ca-export.der").toFile();
        java.io.File serverCsrFile = pluginDir.resolve("server.csr").toFile();
        java.io.File serverCerFile = pluginDir.resolve("server.cer").toFile();

        try {
            StringBuilder san = new StringBuilder("dns:localhost,dns:agentbridge.local,ip:127.0.0.1,ip:127.0.1.1");
            for (String ip : localIps) san.append(",ip:").append(ip);

            // 1. Generate CA key pair + long-lived self-signed CA cert
            runKeytool(new String[]{
                "keytool", "-genkeypair",
                "-alias", "ca",
                "-keyalg", "RSA", "-keysize", "4096", "-validity", "3650",
                "-keystore", caKsFile.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD, "-keypass", KEYSTORE_PASSWORD,
                "-dname", "CN=AgentBridge CA, O=AgentBridge, C=FI",
                "-ext", "BC:critical=ca:true",
                "-ext", "KU:critical=keyCertSign,cRLSign"
            });

            // 2. Generate server key pair (initially self-signed; will be replaced below)
            runKeytool(new String[]{
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "397",
                "-keystore", serverKsFile.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD, "-keypass", KEYSTORE_PASSWORD,
                "-dname", "CN=AgentBridge Server, O=AgentBridge, C=FI"
            });

            // 3. Generate CSR from server key
            runKeytool(new String[]{
                "keytool", "-certreq",
                "-alias", "server",
                "-keystore", serverKsFile.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-file", serverCsrFile.getAbsolutePath()
            });

            // 4. Sign server CSR with CA key → short-lived server cert with SANs and serverAuth EKU
            runKeytool(new String[]{
                "keytool", "-gencert",
                "-alias", "ca",
                "-keystore", caKsFile.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-infile", serverCsrFile.getAbsolutePath(),
                "-outfile", serverCerFile.getAbsolutePath(),
                "-validity", "397",
                "-ext", "SAN=" + san,
                "-ext", "EKU=serverAuth",
                "-ext", "BC:critical=ca:false"
            });

            // 5. Export CA cert as DER so it can be imported as a trusted entry into the server keystore
            runKeytool(new String[]{
                "keytool", "-exportcert",
                "-alias", "ca",
                "-keystore", caKsFile.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-file", caExportFile.getAbsolutePath()
            });

            // 6. Import CA cert as trusted into server keystore — keytool needs this to build the chain
            runKeytool(new String[]{
                "keytool", "-importcert",
                "-alias", "ca",
                "-keystore", serverKsFile.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-file", caExportFile.getAbsolutePath(),
                "-noprompt", "-trustcacerts"
            });

            // 7. Import signed server cert — replaces the temp self-signed cert; chain: server → CA
            runKeytool(new String[]{
                "keytool", "-importcert",
                "-alias", "server",
                "-keystore", serverKsFile.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", KEYSTORE_PASSWORD,
                "-file", serverCerFile.getAbsolutePath(),
                "-noprompt"
            });
        } finally {
            java.nio.file.Files.deleteIfExists(caExportFile.toPath());
            java.nio.file.Files.deleteIfExists(serverCsrFile.toPath());
            java.nio.file.Files.deleteIfExists(serverCerFile.toPath());
        }
    }

    private static void runKeytool(String[] cmd) throws IOException {
        Process process = new ProcessBuilder(cmd).start();
        String error;
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
                try (java.io.InputStream is = process.getErrorStream()) {
                    error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new IOException("keytool " + cmd[1] + " failed: " + error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("keytool " + cmd[1] + " interrupted", e);
        }
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

    private static boolean serverCertCoversAllIps(java.io.File serverKsFile, List<String> requiredIps) {
        if (requiredIps.isEmpty()) return true;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(serverKsFile)) {
                ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
            java.security.cert.Certificate cert = ks.getCertificate("server");
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
            LOG.warn("[ChatWebServer] Could not read server certificate SANs", e);
            return false;
        }
    }

    /**
     * Returns {@code true} if the server keystore contains a cert with the expected subject and
     * {@code agentbridge.local} DNS SAN. Detects keystores from older single-cert format.
     */
    private static boolean serverCertHasExpectedSubject(java.io.File serverKsFile) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(serverKsFile)) {
                ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
            java.security.cert.Certificate cert = ks.getCertificate("server");
            if (!(cert instanceof X509Certificate x509)) return false;
            String subject = x509.getSubjectX500Principal().getName();
            if (!subject.contains(EXPECTED_SERVER_SUBJECT_CN) || !subject.contains(EXPECTED_SERVER_SUBJECT_O))
                return false;
            Collection<List<?>> sans = x509.getSubjectAlternativeNames();
            if (sans == null) return false;
            for (List<?> san : sans) {
                if (san.get(0) instanceof Integer type && type == 2
                    && "agentbridge.local".equals(san.get(1))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOG.warn("[ChatWebServer] Could not read server certificate subject", e);
            return false;
        }
    }

    private void handleCert(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] pemBytes = caCertPemBytes;
        if (pemBytes == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        try {
            // Serve as PEM — Android's CA certificate installer requires PEM format.
            // Serving raw DER triggers Android's VPN/app-cert installer which asks for a private key.
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
            + "self.addEventListener('fetch',e=>{"
            + "if(new URL(e.request.url).pathname==='/events')return;"
            + "const offlineHtml='<!DOCTYPE html><html><body style=\"font-family:sans-serif;padding:2em;text-align:center;background:#1a1a1a;color:#e0e0e0\">"
            + "<h2>Connection failed</h2>"
            + "<p>Could not reach the server. Make sure:</p>"
            + "<ul style=\"text-align:left;display:inline-block\">"
            + "<li>The IDE plugin is running</li>"
            + "<li>Your phone is on the same network as your computer</li>"
            + "<li>The CA certificate is installed (System &rarr; Encryption &rarr; CA certs)</li>"
            + "</ul>"
            + "<p><a href=\"/\" style=\"color:#7ab8ff\">Retry</a></p>"
            + "</body></html>';"
            + "e.respondWith(fetch(e.request).catch(()=>new Response(offlineHtml,{status:503,headers:{'Content-Type':'text/html'}})));"
            + "});\n"
            // Web Push: fetch event data from local server and show notification
            + "self.addEventListener('push',e=>{\n"
            + "  e.waitUntil((async()=>{\n"
            + "    let title='AgentBridge',body='';\n"
            + "    try{\n"
            + "      const data=e.data?JSON.parse(e.data.text()):{};\n"
            + "      title=data.title||'AgentBridge';\n"
            + "      if(data.seq){\n"
            + "        const r=await fetch('/state');\n"
            + "        const st=await r.json();\n"
            + "        const ev=(st.events||[]).slice().reverse().find(ev=>ev.notification&&ev.seq>=data.seq);\n"
            + "        if(ev&&ev.body)body=ev.body;\n"
            + "      }\n"
            + "    }catch(err){}\n"
            + "    await self.registration.showNotification(title,{body,icon:'/icon-192.png',tag:'agentbridge',requireInteraction:false});\n"
            + "  })());\n"
            + "});\n"
            + "self.addEventListener('message',e=>{\n"
            + "  if(e.data&&e.data.type==='SHOW_NOTIFICATION'){\n"
            + "    const opts={body:e.data.body||'',icon:'/icon-192.png',tag:'agentbridge',requireInteraction:false};\n"
            + "    if(e.data.actions&&e.data.actions.length)opts.actions=e.data.actions;\n"
            + "    e.waitUntil(self.registration.showNotification(e.data.title||'AgentBridge',opts));\n"
            + "  }\n"
            + "});\n"
            + "self.addEventListener('notificationclick',e=>{\n"
            + "  e.notification.close();\n"
            + "  e.waitUntil(clients.matchAll({type:'window',includeUncontrolled:true}).then(list=>{\n"
            + "    const c=list.find(w=>w.url.startsWith(self.location.origin));\n"
            + "    if(c)return c.focus();\n"
            + "    return clients.openWindow('/');\n"
            + "  }));\n"
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
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
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
        List<String> certIps = new ArrayList<>();
        if (sslKeyStore != null) {
            try {
                java.security.cert.Certificate cert = sslKeyStore.getCertificate("server");
                if (cert instanceof X509Certificate x509) {
                    Collection<List<?>> sans = x509.getSubjectAlternativeNames();
                    if (sans != null) {
                        for (List<?> san : sans) {
                            // SAN type 7 = iPAddress
                            if (san.get(0) instanceof Integer type && type == 7) {
                                certIps.add((String) san.get(1));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[ChatWebServer] Could not read cert SANs for /info", e);
            }
        }
        String pluginVersion = BuildInfo.getVersion();
        WebPushSender wp = getOrCreateWebPush();
        String vapidKey = wp != null ? wp.getVapidPublicKeyBase64() : "";
        return "{\"project\":" + GSON.toJson(projectName)
            + ",\"model\":" + GSON.toJson(currentModel)
            + ",\"running\":" + agentRunning
            + ",\"connected\":" + connected
            + ",\"version\":" + GSON.toJson(pluginVersion)
            + ",\"certIps\":" + GSON.toJson(certIps)
            + ",\"models\":" + modelsJson
            + ",\"profiles\":" + profilesJson
            + ",\"vapidKey\":" + GSON.toJson(vapidKey) + "}";
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
            + "    <button id=\"ab-menu-btn\" aria-label=\"Menu\" title=\"Menu\">\u2630</button>\n"
            + "  </div>\n"
            + "  <div id=\"ab-menu\" hidden>\n"
            + "    <div id=\"ab-menu-version\"></div>\n"
            + "    <div id=\"ab-menu-model-section\">\n"
            + "      <label id=\"ab-menu-model-label\">Model</label>\n"
            + "      <select id=\"ab-menu-model\"></select>\n"
            + "    </div>\n"
            + "    <button id=\"ab-menu-disconnect\">\u2715\ufe0f Disconnect ACP</button>\n"
            + "    <div class=\"ab-menu-sep\"></div>\n"
            + "    <button id=\"ab-menu-reload\">\ud83d\udd04 Hard reload</button>\n"
            + "  </div>\n"
            + "  <div id=\"ab-connect-page\" hidden>\n"
            + "    <div id=\"ab-connect-wrapper\">\n"
            + "      <div id=\"ab-mcp-card\">\n"
            + "        <div class=\"ab-card-header\">MCP Server</div>\n"
            + "        <div class=\"ab-card-content\">\n"
            + "          <div class=\"ab-status-row\">\n"
            + "            <span class=\"ab-status-label\">Status:</span>\n"
            + "            <span class=\"ab-status-indicator\">\n"
            + "              <span id=\"ab-mcp-dot\" class=\"ab-status-dot\"></span>\n"
            + "              <span id=\"ab-mcp-text\">Initializing</span>\n"
            + "            </span>\n"
            + "          </div>\n"
            + "        </div>\n"
            + "      </div>\n"
            + "      <div id=\"ab-acp-card\">\n"
            + "        <div class=\"ab-card-header\">\n"
            + "          <span>Connect to ACP</span>\n"
            + "          <button id=\"ab-connect-stop-btn\" hidden class=\"ab-card-stop-btn\">⏹</button>\n"
            + "        </div>\n"
            + "        <div class=\"ab-card-content\">\n"
            + "          <select id=\"ab-connect-profile\"></select>\n"
            + "          <button id=\"ab-connect-btn\">Connect</button>\n"
            + "          <div id=\"ab-connect-status\"></div>\n"
            + "        </div>\n"
            + "      </div>\n"
            + "    </div>\n"
            + "  </div>\n"
            + "  <div id=\"ab-chat\"><chat-container></chat-container></div>\n"
            + "  <div id=\"ab-footer\">\n"
            + "    <textarea id=\"ab-input\" rows=\"1\" placeholder=\"Message\u2026\" enterkeyhint=\"send\"></textarea>\n"
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
        + "html,body{height:100%;margin:0;padding:0;background:var(--bg);color:var(--fg);overflow:hidden;}\n"
        + "body{display:flex;flex-direction:column;height:100dvh;font-family:var(--font-family);font-size:var(--font-size);}\n"
        + "#ab-offline{display:none;position:fixed;top:48px;left:0;right:0;background:var(--error);color:#fff;text-align:center;padding:4px 8px;font-size:.85em;z-index:200;}\n"
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
        + "tool-chip .chip-expand{display:none;}\n"
        + "/* Hamburger menu */\n"
        + "#ab-menu-btn{border:none;background:transparent;color:var(--fg);cursor:pointer;font-size:1.1em;padding:2px 6px;border-radius:4px;line-height:1;flex:0 0 auto;}\n"
        + "#ab-menu-btn:hover{background:var(--fg-a08);}\n"
        + "#ab-menu{position:fixed;top:44px;right:8px;z-index:150;background:var(--bg);border:1px solid var(--fg-a16);border-radius:6px;box-shadow:0 4px 16px rgba(0,0,0,.25);min-width:200px;padding:8px 0;}\n"
        + "#ab-menu-version{padding:6px 14px 8px;font-size:.82em;color:var(--fg-muted);border-bottom:1px solid var(--fg-a08);margin-bottom:4px;}\n"
        + "#ab-menu-reload{display:block;width:100%;text-align:left;border:none;background:transparent;color:var(--fg);cursor:pointer;padding:7px 14px;font:inherit;font-size:.9em;}\n"
        + "#ab-menu-reload:hover{background:var(--fg-a08);}\n"
        + "#ab-menu-model-section{padding:6px 14px 8px;border-bottom:1px solid var(--fg-a08);}\n"
        + "#ab-menu-model-label{display:block;font-size:.78em;color:var(--fg-muted);margin-bottom:4px;}\n"
        + "#ab-menu-model{width:100%;background:var(--fg-a05);color:var(--fg);border:1px solid var(--fg-a16);border-radius:4px;padding:4px 6px;font:inherit;font-size:.88em;cursor:pointer;}\n"
        + "#ab-menu-model:focus{outline:1px solid var(--user);}\n"
        + "#ab-menu-disconnect{display:block;width:100%;text-align:left;border:none;background:transparent;color:var(--error,#e06c75);cursor:pointer;padding:7px 14px;font:inherit;font-size:.9em;}\n"
        + "#ab-menu-disconnect:hover{background:var(--fg-a08);}\n"
        + ".ab-menu-sep{height:1px;background:var(--fg-a08);margin:4px 0;}\n"
        + "/* Connect page */\n"
        + "#ab-connect-page{flex:1;display:none;align-items:center;justify-content:center;background:var(--bg);overflow:auto;padding:24px;}\n"
        + "#ab-connect-page:not([hidden]){display:flex;}\n"
        + "#ab-connect-wrapper{display:flex;flex-direction:column;gap:16px;width:min(360px,90vw);max-height:600px;}\n"
        + "#ab-mcp-card,#ab-acp-card{display:flex;flex-direction:column;gap:8px;background:var(--bg);border:1px solid var(--fg-a16);border-radius:10px;padding:16px;}\n"
        + ".ab-card-header{font-weight:600;font-size:1em;display:flex;align-items:center;justify-content:space-between;}\n"
        + ".ab-card-content{display:flex;flex-direction:column;gap:8px;}\n"
        + ".ab-status-row{display:flex;align-items:center;justify-content:space-between;gap:12px;}\n"
        + ".ab-status-label{font-size:.85em;color:var(--fg-muted);min-width:60px;}\n"
        + ".ab-status-indicator{display:flex;align-items:center;gap:6px;flex:1;}\n"
        + ".ab-status-dot{width:10px;height:10px;border-radius:50%;background:var(--fg-a16);flex:0 0 10px;transition:background .3s;}\n"
        + ".ab-status-dot.connected{background:var(--kind-execute);}\n"
        + ".ab-status-dot.running{background:var(--agent);animation:ab-pulse 1.5s infinite;}\n"
        + "#ab-mcp-text{font-size:.9em;color:var(--fg);}\n"
        + ".ab-card-stop-btn{border:none;background:transparent;color:var(--error,#e06c75);cursor:pointer;font-size:.95em;padding:4px 8px;border-radius:4px;flex:0 0 auto;}\n"
        + ".ab-card-stop-btn:hover{background:var(--fg-a08);}\n"
        + "#ab-connect-profile{background:var(--fg-a05);color:var(--fg);border:1px solid var(--fg-a16);border-radius:6px;padding:7px 10px;font:inherit;cursor:pointer;width:100%;box-sizing:border-box;}\n"
        + "#ab-connect-profile:focus{outline:1px solid var(--user);}\n"
        + "#ab-connect-btn{border:none;border-radius:6px;padding:9px 16px;background:var(--user-a12);color:var(--user);cursor:pointer;font:inherit;font-weight:600;font-size:.95em;width:100%;}\n"
        + "#ab-connect-btn:hover{background:var(--user-a16);}\n"
        + "#ab-connect-btn:disabled{opacity:.4;cursor:default;}\n"
        + "#ab-connect-status{font-size:.85em;color:var(--fg-muted);min-height:1.2em;}\n";

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
        + "const menuBtn=document.getElementById('ab-menu-btn');\n"
        + "const menuEl=document.getElementById('ab-menu');\n"
        + "const menuVersionEl=document.getElementById('ab-menu-version');\n"
        + "const menuReloadBtn=document.getElementById('ab-menu-reload');\n"
        + "const menuModelSel=document.getElementById('ab-menu-model');\n"
        + "const menuDisconnectBtn=document.getElementById('ab-menu-disconnect');\n"
        + "const connectPageEl=document.getElementById('ab-connect-page');\n"
        + "const connectProfileSel=document.getElementById('ab-connect-profile');\n"
        + "const connectBtn=document.getElementById('ab-connect-btn');\n"
        + "const connectStatusEl=document.getElementById('ab-connect-status');\n"
        + "const connectStopBtn=document.getElementById('ab-connect-stop-btn');\n"
        + "const mcpDot=document.getElementById('ab-mcp-dot');\n"
        + "const mcpText=document.getElementById('ab-mcp-text');\n"
        + "const chatAreaEl=document.getElementById('ab-chat');\n"
        + "const footerEl=document.getElementById('ab-footer');\n"
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
        + "ChatController.setCurrentModel=function(m){_origSCM(m);modelEl.textContent=m?m.substring(m.lastIndexOf('/')+1):'';syncModelSelect(m);};\n"
        + "function updateButtons(){"
        + "  statusDot.className=agentRunning?'running':'connected';"
        + "  mcpDot.className=agentRunning?'running':'connected';"
        + "  mcpText.textContent=agentRunning?'Running':'Ready';"
        + "  connectStopBtn.hidden=!agentRunning;"
        + "  sendBtn.innerHTML=ICON_SVG + '<span>' + (agentRunning?'Nudge':'Send') + '</span>';"
        + "}\n"
        + "ChatController.setClientType=(type,iconSvg)=>{"
        + "  if(iconSvg)window.ICON_SVG=iconSvg.replace('<svg','<svg style=\"vertical-align:text-bottom;margin-right:4px\" fill=\"currentColor\" width=\"14\" height=\"14\"');"
        + "  updateButtons();"
        + "};\n"
        // Connection state helpers
        + "function showChatView(){"
        + "  connectPageEl.hidden=true;"
        + "  chatAreaEl.style.display='';"
        + "  footerEl.style.display='';"
        + "  menuDisconnectBtn.style.display='';"
        + "  document.getElementById('ab-menu-model-section').style.display='';"
        + "}\n"
        + "function showConnectView(profiles){"
        + "  chatAreaEl.style.display='none';"
        + "  footerEl.style.display='none';"
        + "  connectPageEl.hidden=false;"
        + "  menuDisconnectBtn.style.display='none';"
        + "  document.getElementById('ab-menu-model-section').style.display='none';"
        + "  connectStatusEl.textContent='';"
        + "  connectBtn.disabled=false;"
        + "  connectBtn.textContent='Connect';"
        + "  connectStopBtn.hidden=!agentRunning;"
        + "  mcpDot.className=agentRunning?'running':'connected';"
        + "  mcpText.textContent=agentRunning?'Running':'Ready';"
        + "  if(profiles&&profiles.length){"
        + "    const prev=connectProfileSel.value;"
        + "    connectProfileSel.innerHTML=profiles.map(p=>`<option value=\"${p.id}\">${p.name}</option>`).join('');"
        + "    if(prev)connectProfileSel.value=prev;"
        + "  }"
        + "}\n"
        // Populate model select from info
        + "function populateModels(models,currentModelId){"
        + "  menuModelSel.innerHTML=(models||[]).map(m=>`<option value=\"${m.id}\">${m.name}</option>`).join('');"
        + "  if(currentModelId)syncModelSelect(currentModelId);"
        + "}\n"
        + "function syncModelSelect(modelId){"
        + "  if(modelId)menuModelSel.value=modelId;"
        + "}\n"
        // Info fetch
        + "let _pluginVersion='';\n"
        + "fetch('/info').then(r=>r.json()).then(info=>{"
        + "  if(info.model){modelEl.textContent=info.model.substring(info.model.lastIndexOf('/')+1);}\n"
        + "  agentRunning=info.running||false;updateButtons();"
        + "  _pluginVersion=info.version||'';"
        + "  populateModels(info.models,info.model);"
        + "  if(info.connected)showChatView();else showConnectView(info.profiles);"
        + "}).catch(()=>{});\n"
        // Hamburger menu
        + "menuBtn.addEventListener('click',e=>{"
        + "  e.stopPropagation();"
        + "  const open=!menuEl.hidden;"
        + "  menuEl.hidden=open;"
        + "  if(!open)menuVersionEl.textContent='Plugin v'+(_pluginVersion||'?');"
        + "});\n"
        + "document.addEventListener('click',e=>{"
        + "  if(!menuEl.hidden&&!menuEl.contains(e.target))menuEl.hidden=true;"
        + "});\n"
        // Hard reload — navigate to /?v=timestamp to bypass HTTP cache
        + "menuReloadBtn.addEventListener('click',()=>{"
        + "  menuEl.hidden=true;"
        + "  if('serviceWorker'in navigator){"
        + "    navigator.serviceWorker.getRegistrations().then(regs=>Promise.all(regs.map(r=>r.unregister())));"
        + "    if('caches'in window)caches.keys().then(keys=>Promise.all(keys.map(k=>caches.delete(k))));"
        + "  }"
        + "  setTimeout(()=>{location.href='/?v='+Date.now();},150);"
        + "});\n"
        // Model select change
        + "menuModelSel.addEventListener('change',()=>{"
        + "  const id=menuModelSel.value;if(id)webPost('/set-model',{modelId:id});"
        + "});\n"
        // Disconnect
        + "menuDisconnectBtn.addEventListener('click',()=>{"
        + "  menuEl.hidden=true;"
        + "  webPost('/disconnect',{});"
        + "});\n"
        // Connect page submit
        + "connectBtn.addEventListener('click',()=>{"
        + "  const profileId=connectProfileSel.value;if(!profileId)return;"
        + "  connectBtn.disabled=true;connectBtn.textContent='Connecting\u2026';"
        + "  connectStatusEl.textContent='';"
        + "  webPost('/connect',{profileId}).catch(()=>{"
        + "    connectBtn.disabled=false;connectBtn.textContent='Connect';"
        + "    connectStatusEl.textContent='Connection error \u2014 check the IDE plugin.';"
        + "  });"
        + "});\n"
        + "// Connect page stop button\n"
        + "connectStopBtn.addEventListener('click',()=>{"
        + "  webPost('/stop',{});"
        + "});\n"
        // handleConnected / handleDisconnected — called via SSE broadcastTransient
        + "function handleConnected(modelsJsonStr,profilesJsonStr){"
        + "  try{"
        + "    const models=JSON.parse(modelsJsonStr||'[]');"
        + "    const profiles=JSON.parse(profilesJsonStr||'[]');"
        + "    populateModels(models,'');"
        + "    fetch('/info').then(r=>r.json()).then(info=>{"
        + "      if(info.model)modelEl.textContent=info.model.substring(info.model.lastIndexOf('/')+1);"
        + "      populateModels(info.models,info.model);"
        + "    }).catch(()=>{});"
        + "    showChatView();"
        + "  }catch(e){showChatView();}"
        + "}\n"
        + "function handleDisconnected(profilesJsonStr){"
        + "  try{"
        + "    const profiles=JSON.parse(profilesJsonStr||'[]');"
        + "    showConnectView(profiles);"
        + "  }catch(e){showConnectView([]);}"
        + "}\n"
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
        + "function showNotification(title,body,actions){"
        + "  if(navigator.serviceWorker&&navigator.serviceWorker.controller){"
        + "    navigator.serviceWorker.controller.postMessage({type:'SHOW_NOTIFICATION',title,body,actions});"
        + "  }else if('Notification'in window&&Notification.permission==='granted'){"
        + "    try{new Notification(title,{body,icon:'/icon.svg',tag:'ab'});}catch(e){}"
        + "  }"
        + "}\n"
        + "function subscribePush(vapidKey){"
        + "  if(!('serviceWorker'in navigator&&'PushManager'in window)){console.warn('[AB] Push not supported');return;}"
        + "  navigator.serviceWorker.ready.then(reg=>{"
        + "    reg.pushManager.getSubscription().then(existing=>{"
        + "      if(existing){console.log('[AB] Push subscription exists');webPost('/push-subscribe',existing.toJSON()).catch(e=>console.error('[AB] Failed to post existing sub:',e));return;}"
        + "      try{"
        + "        const appKey=Uint8Array.from(atob(vapidKey.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));"
        + "        reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:appKey})"
        + "          .then(sub=>{console.log('[AB] Subscribed to push');webPost('/push-subscribe',sub.toJSON()).catch(e=>console.error('[AB] Failed to post new sub:',e));})"
        + "          .catch(e=>console.error('[AB] Subscribe failed:',e));"
        + "      }catch(e){console.error('[AB] Push key decode error:',e);}"
        + "    }).catch(e=>console.error('[AB] getSubscription error:',e));"
        + "  }).catch(e=>console.error('[AB] serviceWorker.ready error:',e));"
        + "}\n"
        + "function reqNotifPerm(){"
        + "  if('Notification'in window){"
        + "    console.log('[AB] Notification permission:', Notification.permission);"
        + "    if(Notification.permission==='default'){"
        + "      Notification.requestPermission().then(p=>{"
        + "        console.log('[AB] Permission result:', p);"
        + "        if(p==='granted')fetch('/info').then(r=>r.json()).then(info=>{console.log('[AB] VAPID key present:', !!info.vapidKey);if(info.vapidKey)subscribePush(info.vapidKey);}).catch(e=>console.error('[AB] Failed to fetch info:',e));"
        + "      }).catch(e=>console.error('[AB] requestPermission error:',e));"
        + "    }"
        + "  }else{console.warn('[AB] Notification API not supported');}"
        + "}\n"
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
        // Register service worker; once ready, subscribe to push if permission already granted
        + "if('serviceWorker'in navigator){\n"
        + "  navigator.serviceWorker.register('/sw.js').then(()=>{\n"
        + "    console.log('[AB] Service worker registered');\n"
        + "    if(Notification.permission==='granted'){\n"
        + "      console.log('[AB] Notification permission granted, subscribing to push...');\n"
        + "      fetch('/info').then(r=>r.json()).then(info=>{if(info.vapidKey)subscribePush(info.vapidKey);}).catch(e=>console.error('[AB] Push subscribe error:',e));\n"
        + "    }else{\n"
        + "      console.log('[AB] Notification permission: '+Notification.permission);\n"
        + "    }\n"
        + "  }).catch(e=>console.error('[AB] SW register failed:',e));\n"
        + "}else{\n"
        + "  console.warn('[AB] Service Worker not supported');\n"
        + "}\n";

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
