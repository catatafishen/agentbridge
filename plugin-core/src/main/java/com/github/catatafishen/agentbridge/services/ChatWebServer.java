package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.BuildInfo;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.settings.ChatHistorySettings;
import com.github.catatafishen.agentbridge.settings.ChatWebServerSettings;
import com.github.catatafishen.agentbridge.ui.ChatTheme;
import com.github.catatafishen.agentbridge.ui.MessageFormatter;
import com.google.gson.Gson;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
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
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    private static final Gson GSON = new Gson();
    private static final String JS_CONTENT_TYPE = "text/javascript; charset=utf-8";
    private static final String SEQ_PREFIX = "{\"seq\":";
    private static final String HDR_CONTENT_TYPE = "Content-Type";
    private static final String HDR_CACHE_CONTROL = "Cache-Control";
    private static final String HDR_ACCESS_CONTROL_ORIGIN = "Access-Control-Allow-Origin";
    private static final String CACHE_NO_CACHE = "no-cache";
    private static final String CACHE_PUBLIC = "public, max-age=86400";

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
    // Stored as raw JSON strings with seq (sequence number) and js (JavaScript event payload) fields
    private final List<String> eventLog = new ArrayList<>();
    private int nextSeq = 1;

    // ── SSE clients ───────────────────────────────────────────────────────────
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();

    // ── Web Push ──────────────────────────────────────────────────────────────
    private volatile WebPushSender webPush;

    // ── HTTP executor ─────────────────────────────────────────────────────────
    private volatile java.util.concurrent.ExecutorService serverExecutor;

    // ── Current state (for /info) ─────────────────────────────────────────────
    private volatile String currentModel = "";
    private volatile String projectName = "";
    private volatile boolean agentRunning = false;
    private volatile boolean connected = false;
    private volatile String modelsJson = "[]";
    private volatile String profilesJson = "[]";

    // ── Action callbacks (wired by ChatToolWindowContent) ─────────────────────
    private volatile Consumer<String> onSendPrompt;
    private volatile Consumer<String> onQuickReply;
    private volatile Consumer<String> onNudge;
    private volatile Runnable onStop;
    private volatile Consumer<String> onCancelNudge;
    /**
     * Permission response: "reqId:deny" / "reqId:once" / "reqId:session"
     */
    private volatile Consumer<String> onPermissionResponse;
    private volatile Runnable onDisconnect;
    private volatile Consumer<String> onConnect;
    private volatile Consumer<String> onSelectModel;
    private volatile Runnable onLoadMore;

    public ChatWebServer(@NotNull Project project) {
        this.project = project;
        projectName = project.getName();
    }

    public static ChatWebServer getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ChatWebServer.class);
    }

    // ── Action callbacks (called by ChatToolWindowContent) ─────────────────────

    public void setOnSendPrompt(Consumer<String> onSendPrompt) {
        this.onSendPrompt = onSendPrompt;
    }

    public void setOnQuickReply(Consumer<String> onQuickReply) {
        this.onQuickReply = onQuickReply;
    }

    public void setOnNudge(Consumer<String> onNudge) {
        this.onNudge = onNudge;
    }

    public void setOnStop(Runnable onStop) {
        this.onStop = onStop;
    }

    public void setOnCancelNudge(Consumer<String> onCancelNudge) {
        this.onCancelNudge = onCancelNudge;
    }

    public void setOnPermissionResponse(Consumer<String> onPermissionResponse) {
        this.onPermissionResponse = onPermissionResponse;
    }

    public void setOnDisconnect(Runnable onDisconnect) {
        this.onDisconnect = onDisconnect;
    }

    public void setOnConnect(Consumer<String> onConnect) {
        this.onConnect = onConnect;
    }

    public void setOnSelectModel(Consumer<String> onSelectModel) {
        this.onSelectModel = onSelectModel;
    }

    public void setOnLoadMore(Runnable onLoadMore) {
        this.onLoadMore = onLoadMore;
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
                String basePath = project.getBasePath();
                java.nio.file.Path subscriptionsFile = basePath != null
                    ? java.nio.file.Paths.get(basePath, ".idea", "push-subscriptions.json")
                    : null;
                webPush = new WebPushSender(kp, subscriptionsFile);
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
        serverExecutor = executor;

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
        server.createContext("/chat.bundle.js", ex -> serveClasspath(ex, "/chat/chat-components.js", JS_CONTENT_TYPE));
        server.createContext("/web-app.js", ex -> serveClasspath(ex, "/chat/web-app.js", JS_CONTENT_TYPE));
        server.createContext("/web-app.css", ex -> serveClasspath(ex, "/chat/web-app.css", "text/css; charset=utf-8"));
        server.createContext("/icon.svg", this::handleIconSvg);
        server.createContext("/icon-192.png", ex -> handleIconPng(ex, 192));
        server.createContext("/icon-512.png", ex -> handleIconPng(ex, 512));
        server.createContext("/badge-96.png", this::handleBadgePng);
        server.createContext("/manifest.json", ex -> serveClasspath(ex, "/chat/manifest.json", "application/json; charset=utf-8"));
        server.createContext("/sw.js", ex -> serveClasspath(ex, "/chat/sw.js", JS_CONTENT_TYPE));
        server.createContext("/cert.crt", this::handleCert);
        server.createContext("/events", this::handleSse);
        server.createContext("/state", this::handleState);
        server.createContext("/catch-up", this::handleCatchUp);
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
        server.createContext("/load-more", ex -> handleAction(ex, body -> {
            if (onLoadMore != null) onLoadMore.run();
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
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
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
            if (!isClear) {
                compactStreamingEvents(js);
            }
            seq = nextSeq++;
            json = SEQ_PREFIX + seq + ",\"js\":" + GSON.toJson(js) + "}";
            if (isClear) {
                // Don't persist clear in the log — new clients start with an empty page
                eventLog.clear();
            } else {
                eventLog.add(json);
                int maxEvents = ChatHistorySettings.getInstance(project).getEventLogSize();
                if (eventLog.size() > maxEvents) eventLog.remove(0);
            }
            // Track model from setCurrentModel calls
            if (js.startsWith("ChatController.setCurrentModel(")) {
                currentModel = extractFirstStringArg(js);
            }
        }
        broadcast(json);
    }

    private void compactStreamingEvents(String js) {
        EventLogCompactor.compactStreamingEvents(js, eventLog);
    }

    static @Nullable String buildStreamingPrefix(String js, String finalizePrefix, String streamPrefix) {
        return EventLogCompactor.buildStreamingPrefix(js, finalizePrefix, streamPrefix);
    }

    static boolean eventJsStartsWith(String eventJson, String jsPrefix) {
        return EventLogCompactor.eventJsStartsWith(eventJson, jsPrefix);
    }

    /**
     * Pushes a notification to live SSE clients and, if any Web Push subscriptions are registered,
     * sends a Web Push to devices that may have the browser closed.
     *
     * <p><b>Security:</b> The Web Push payload intentionally contains only the event sequence
     * number and title — never the notification body. Push payloads travel through third-party
     * push services (Google FCM, Apple APNs, Mozilla autopush) and may contain sensitive
     * information (code snippets, file paths, error messages). The service worker fetches the
     * actual body from the local server via {@code /state} after receiving the push, keeping
     * sensitive content on the local network only.</p>
     */
    public void pushNotification(@NotNull String title, @NotNull String body) {
        if (!running) return;
        int seq;
        synchronized (this) {
            seq = nextSeq++;
        }
        String json = SEQ_PREFIX + seq + ",\"notification\":true,\"title\":"
            + GSON.toJson(title) + ",\"body\":" + GSON.toJson(body) + "}";
        broadcast(json);
        // Also send via Web Push for devices with the browser closed.
        // Only seq + title — never body (see Javadoc above).
        WebPushSender wp = webPush; // read volatile once; null if not yet initialised
        if (wp != null) {
            if (wp.hasSubscriptions()) {
                String payload = SEQ_PREFIX + seq + ",\"title\":" + GSON.toJson(title) + "}";
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

    private static final String KEYSTORE_PASSWORD_SERVICE = "AgentBridge/KeystorePassword";
    private static final String LEGACY_KEYSTORE_PASSWORD = "agentbridge-ephemeral";
    private static final String PKCS12_TYPE = "PKCS12";
    private static final String KEYTOOL_CMD = "keytool";
    private static final String KT_GENKEYPAIR = "-genkeypair";
    private static final String KT_ALIAS = "-alias";
    private static final String KT_ALIAS_SERVER = "server";
    private static final String KT_KEYALG = "-keyalg";
    private static final String KT_KEYSIZE = "-keysize";
    private static final String KT_VALIDITY = "-validity";
    private static final String KT_KEYSTORE = "-keystore";
    private static final String KT_STORETYPE = "-storetype";
    private static final String KT_STOREPASS = "-storepass";
    private static final String KT_KEYPASS = "-keypass";
    private static final String KT_DNAME = "-dname";
    private static final String KT_EXT = "-ext";
    private static final String KT_FILE = "-file";
    private static final String KT_NOPROMPT = "-noprompt";
    private static final String KT_IMPORTCERT = "-importcert";
    private static final long KEYTOOL_TIMEOUT_SECONDS = 120;
    private static final long PROCESS_TERMINATION_TIMEOUT_SECONDS = 2;
    private static final int MAX_PROCESS_OUTPUT_CHARS = 4096;

    /**
     * Returns the keystore password, generating and persisting a random one if none is stored yet.
     * The password is kept in IntelliJ's PasswordSafe so it survives IDE restarts without being
     * committed to source code.
     */
    private static String getOrCreateKeystorePassword() {
        CredentialAttributes attrs = new CredentialAttributes(KEYSTORE_PASSWORD_SERVICE);
        Credentials creds = PasswordSafe.getInstance().get(attrs);
        if (creds != null) {
            String pwd = creds.getPasswordAsString();
            if (pwd != null && !pwd.isEmpty()) return pwd;
        }
        String newPwd = UUID.randomUUID().toString();
        PasswordSafe.getInstance().set(attrs, new Credentials("keystore", newPwd));
        return newPwd;
    }

    /**
     * Returns {@code true} if the given PKCS12 keystore file can be loaded with the current
     * stored password. Used to detect keystores created under a different password and force
     * regeneration.
     */
    private static boolean canLoadKeystore(java.io.File ksFile) {
        return canLoadKeystore(ksFile, getOrCreateKeystorePassword());
    }

    private static boolean canLoadKeystore(java.io.File ksFile, String password) {
        try {
            KeyStore ks = KeyStore.getInstance(PKCS12_TYPE);
            try (java.io.FileInputStream fis = new java.io.FileInputStream(ksFile)) {
                ks.load(fis, password.toCharArray());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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
    private SSLContext buildSslContext() throws GeneralSecurityException, IOException {
        java.nio.file.Path pluginDir = getPluginDir();
        java.io.File caKsFile = pluginDir.resolve("ca.p12").toFile();
        java.io.File serverKsFile = pluginDir.resolve("server.p12").toFile();

        List<String> localIps = collectLocalIpv4Addresses();

        String ksPassword = prepareKeystorePassword(pluginDir, caKsFile, serverKsFile);
        boolean caNeedsRegen = !caKsFile.exists() || !canLoadKeystore(caKsFile, ksPassword);
        boolean serverNeedsRegen = caNeedsRegen
            || !serverKsFile.exists()
            || !canLoadKeystore(serverKsFile, ksPassword)
            || !serverCertCoversAllIps(serverKsFile, localIps, ksPassword)
            || !serverCertHasExpectedSubject(serverKsFile, ksPassword);

        if (caNeedsRegen) {
            LOG.info("[ChatWebServer] Generating new CA + server certificates");
            java.nio.file.Files.createDirectories(pluginDir);
            deleteCertificateStoresForCaRegeneration(caKsFile, serverKsFile);
            generateCaPlusServerCerts(pluginDir, caKsFile, serverKsFile, localIps);
        } else if (serverNeedsRegen) {
            // Preserve existing CA so devices that already installed it keep trusting us.
            LOG.info("[ChatWebServer] Regenerating server certificate only (CA unchanged)");
            java.nio.file.Files.deleteIfExists(serverKsFile.toPath());
            java.nio.file.Files.createDirectories(pluginDir);
            regenerateServerCert(pluginDir, caKsFile, serverKsFile, localIps);
        }

        // Load CA cert for device installation at /cert.crt
        KeyStore caKs = KeyStore.getInstance(PKCS12_TYPE);
        try (java.io.FileInputStream fis = new java.io.FileInputStream(caKsFile)) {
            caKs.load(fis, ksPassword.toCharArray());
        }
        byte[] derBytes = caKs.getCertificate("ca").getEncoded();
        String pem = "-----BEGIN CERTIFICATE-----\n"
            + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(derBytes)
            + "\n-----END CERTIFICATE-----\n";
        caCertPemBytes = pem.getBytes(StandardCharsets.UTF_8);

        // Load server keystore for the HTTPS handshake
        KeyStore serverKs = KeyStore.getInstance(PKCS12_TYPE);
        try (java.io.FileInputStream fis = new java.io.FileInputStream(serverKsFile)) {
            serverKs.load(fis, ksPassword.toCharArray());
        }
        sslKeyStore = serverKs;

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverKs, ksPassword.toCharArray());

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static String prepareKeystorePassword(
        java.nio.file.Path pluginDir,
        java.io.File caKsFile,
        java.io.File serverKsFile) throws IOException {

        String currentPassword = getOrCreateKeystorePassword();
        if (!caKsFile.exists() || canLoadKeystore(caKsFile, currentPassword)) {
            return currentPassword;
        }
        if (!canLoadKeystore(caKsFile, LEGACY_KEYSTORE_PASSWORD)) {
            return currentPassword;
        }

        LOG.info("[ChatWebServer] Migrating legacy TLS keystores to PasswordSafe-backed password");
        java.nio.file.Files.createDirectories(pluginDir);
        migrateKeystorePassword(caKsFile, LEGACY_KEYSTORE_PASSWORD, currentPassword);
        if (!serverKsFile.exists()) {
            return currentPassword;
        }
        if (canLoadKeystore(serverKsFile, LEGACY_KEYSTORE_PASSWORD)) {
            migrateKeystorePassword(serverKsFile, LEGACY_KEYSTORE_PASSWORD, currentPassword);
        } else if (!canLoadKeystore(serverKsFile, currentPassword)) {
            // The CA is preserved; a stale/unloadable server certificate can be regenerated from it.
            java.nio.file.Files.deleteIfExists(serverKsFile.toPath());
        }
        return currentPassword;
    }

    private static void migrateKeystorePassword(java.io.File keystoreFile, String oldPassword, String newPassword)
        throws IOException {

        try {
            KeyStore source = KeyStore.getInstance(PKCS12_TYPE);
            char[] oldPasswordChars = oldPassword.toCharArray();
            char[] newPasswordChars = newPassword.toCharArray();
            try (java.io.FileInputStream input = new java.io.FileInputStream(keystoreFile)) {
                source.load(input, oldPasswordChars);
            }

            KeyStore migrated = KeyStore.getInstance(PKCS12_TYPE);
            migrated.load(null, newPasswordChars);
            Enumeration<String> aliases = source.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (source.isKeyEntry(alias)) {
                    Key key = source.getKey(alias, oldPasswordChars);
                    java.security.cert.Certificate[] chain = source.getCertificateChain(alias);
                    migrated.setKeyEntry(alias, key, newPasswordChars, chain);
                } else if (source.isCertificateEntry(alias)) {
                    migrated.setCertificateEntry(alias, source.getCertificate(alias));
                }
            }
            replaceKeystoreAtomically(keystoreFile.toPath(), migrated, newPasswordChars);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to migrate TLS keystore password for " + keystoreFile.getName(), e);
        }
    }

    private static void replaceKeystoreAtomically(java.nio.file.Path keystorePath, KeyStore migrated, char[] password)
        throws IOException, GeneralSecurityException {

        java.nio.file.Path parent = keystorePath.getParent();
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(parent, keystorePath.getFileName().toString(), ".tmp");
        try {
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(tempFile)) {
                migrated.store(output, password);
            }
            try {
                java.nio.file.Files.move(tempFile, keystorePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tempFile, keystorePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    private static void deleteCertificateStoresForCaRegeneration(
        java.io.File caKsFile,
        java.io.File serverKsFile) throws IOException {

        java.nio.file.Files.deleteIfExists(serverKsFile.toPath());
        java.nio.file.Files.deleteIfExists(caKsFile.toPath());
    }

    /**
     * Generates a CA key pair + self-signed CA cert, then a server key pair signed by the CA.
     * Uses keytool for all crypto operations (no external library required).
     */
    private static void generateCaPlusServerCerts(
        java.nio.file.Path pluginDir,
        java.io.File caKsFile,
        java.io.File serverKsFile,
        List<String> localIps) throws IOException {

        java.io.File caExportFile = pluginDir.resolve("ca-export.der").toFile();
        java.io.File serverCsrFile = pluginDir.resolve("server.csr").toFile();
        java.io.File serverCerFile = pluginDir.resolve("server.cer").toFile();

        String ksPassword = getOrCreateKeystorePassword();
        try {
            generateCaCertificate(caKsFile, ksPassword);
            generateServerKeyPair(serverKsFile, ksPassword);
            generateServerCertificateChain(caKsFile, serverKsFile, caExportFile, serverCsrFile, serverCerFile,
                buildSubjectAlternativeNames(localIps), ksPassword);
        } finally {
            java.nio.file.Files.deleteIfExists(caExportFile.toPath());
            java.nio.file.Files.deleteIfExists(serverCsrFile.toPath());
            java.nio.file.Files.deleteIfExists(serverCerFile.toPath());
        }
    }

    /**
     * Regenerates only the server certificate, signed by the existing CA.
     * <p>
     * Call this when the server cert is stale (e.g., IPs changed) but the CA is still valid.
     * Preserving the CA means devices that already installed the CA cert continue to trust us.
     */
    private static void regenerateServerCert(
        java.nio.file.Path pluginDir,
        java.io.File caKsFile,
        java.io.File serverKsFile,
        List<String> localIps) throws IOException {

        java.io.File caExportFile = pluginDir.resolve("ca-export.der").toFile();
        java.io.File serverCsrFile = pluginDir.resolve("server.csr").toFile();
        java.io.File serverCerFile = pluginDir.resolve("server.cer").toFile();

        String ksPassword = getOrCreateKeystorePassword();
        try {
            generateServerKeyPair(serverKsFile, ksPassword);
            generateServerCertificateChain(caKsFile, serverKsFile, caExportFile, serverCsrFile, serverCerFile,
                buildSubjectAlternativeNames(localIps), ksPassword);
        } finally {
            java.nio.file.Files.deleteIfExists(caExportFile.toPath());
            java.nio.file.Files.deleteIfExists(serverCsrFile.toPath());
            java.nio.file.Files.deleteIfExists(serverCerFile.toPath());
        }
    }

    private static String buildSubjectAlternativeNames(List<String> localIps) {
        StringBuilder san = new StringBuilder("dns:localhost,dns:agentbridge.local,ip:127.0.0.1,ip:127.0.1.1");
        for (String ip : localIps) {
            san.append(",ip:").append(ip);
        }
        return san.toString();
    }

    private static void generateCaCertificate(java.io.File caKsFile, String ksPassword) throws IOException {
        runKeytool(new String[]{
            KEYTOOL_CMD, KT_GENKEYPAIR,
            KT_ALIAS, "ca",
            KT_KEYALG, "RSA", KT_KEYSIZE, "4096", KT_VALIDITY, "3650",
            KT_KEYSTORE, caKsFile.getAbsolutePath(), KT_STORETYPE, PKCS12_TYPE,
            KT_STOREPASS, ksPassword, KT_KEYPASS, ksPassword,
            KT_DNAME, "CN=AgentBridge CA, O=AgentBridge, C=FI",
            KT_EXT, "BC:critical=ca:true",
            KT_EXT, "KU:critical=keyCertSign,cRLSign"
        });
    }

    private static void generateServerKeyPair(java.io.File serverKsFile, String ksPassword) throws IOException {
        runKeytool(new String[]{
            KEYTOOL_CMD, KT_GENKEYPAIR,
            KT_ALIAS, KT_ALIAS_SERVER,
            KT_KEYALG, "RSA", KT_KEYSIZE, "2048", KT_VALIDITY, "397",
            KT_KEYSTORE, serverKsFile.getAbsolutePath(), KT_STORETYPE, PKCS12_TYPE,
            KT_STOREPASS, ksPassword, KT_KEYPASS, ksPassword,
            KT_DNAME, "CN=AgentBridge Server, O=AgentBridge, C=FI"
        });
    }

    private static void generateServerCertificateChain(
        java.io.File caKsFile,
        java.io.File serverKsFile,
        java.io.File caExportFile,
        java.io.File serverCsrFile,
        java.io.File serverCerFile,
        String subjectAlternativeNames,
        String ksPassword) throws IOException {

        generateServerCertificateRequest(serverKsFile, serverCsrFile, ksPassword);
        signServerCertificate(caKsFile, serverCsrFile, serverCerFile, subjectAlternativeNames, ksPassword);
        exportCaCertificate(caKsFile, caExportFile, ksPassword);
        importCaCertificate(serverKsFile, caExportFile, ksPassword);
        importSignedServerCertificate(serverKsFile, serverCerFile, ksPassword);
    }

    private static void generateServerCertificateRequest(
        java.io.File serverKsFile,
        java.io.File serverCsrFile,
        String ksPassword) throws IOException {

        runKeytool(new String[]{
            KEYTOOL_CMD, "-certreq",
            KT_ALIAS, KT_ALIAS_SERVER,
            KT_KEYSTORE, serverKsFile.getAbsolutePath(), KT_STORETYPE, PKCS12_TYPE,
            KT_STOREPASS, ksPassword,
            KT_FILE, serverCsrFile.getAbsolutePath()
        });
    }

    private static void signServerCertificate(
        java.io.File caKsFile,
        java.io.File serverCsrFile,
        java.io.File serverCerFile,
        String subjectAlternativeNames,
        String ksPassword) throws IOException {

        runKeytool(new String[]{
            KEYTOOL_CMD, "-gencert",
            KT_ALIAS, "ca",
            KT_KEYSTORE, caKsFile.getAbsolutePath(), KT_STORETYPE, PKCS12_TYPE,
            KT_STOREPASS, ksPassword,
            "-infile", serverCsrFile.getAbsolutePath(),
            "-outfile", serverCerFile.getAbsolutePath(),
            KT_VALIDITY, "397",
            KT_EXT, "SAN=" + subjectAlternativeNames,
            KT_EXT, "EKU=serverAuth",
            KT_EXT, "BC:critical=ca:false"
        });
    }

    private static void exportCaCertificate(java.io.File caKsFile, java.io.File caExportFile, String ksPassword)
        throws IOException {

        runKeytool(new String[]{
            KEYTOOL_CMD, "-exportcert",
            KT_ALIAS, "ca",
            KT_KEYSTORE, caKsFile.getAbsolutePath(), KT_STORETYPE, PKCS12_TYPE,
            KT_STOREPASS, ksPassword,
            KT_FILE, caExportFile.getAbsolutePath()
        });
    }

    private static void importCaCertificate(java.io.File serverKsFile, java.io.File caExportFile, String ksPassword)
        throws IOException {

        runKeytool(new String[]{
            KEYTOOL_CMD, KT_IMPORTCERT,
            KT_ALIAS, "ca",
            KT_KEYSTORE, serverKsFile.getAbsolutePath(), KT_STORETYPE, PKCS12_TYPE,
            KT_STOREPASS, ksPassword,
            KT_FILE, caExportFile.getAbsolutePath(),
            KT_NOPROMPT, "-trustcacerts"
        });
    }

    private static void importSignedServerCertificate(
        java.io.File serverKsFile,
        java.io.File serverCerFile,
        String ksPassword) throws IOException {

        runKeytool(new String[]{
            KEYTOOL_CMD, KT_IMPORTCERT,
            KT_ALIAS, KT_ALIAS_SERVER,
            KT_KEYSTORE, serverKsFile.getAbsolutePath(), KT_STORETYPE, PKCS12_TYPE,
            KT_STOREPASS, ksPassword,
            KT_FILE, serverCerFile.getAbsolutePath(),
            KT_NOPROMPT
        });
    }

    /**
     * Resolves the path to the {@code keytool} executable bundled with the running JDK.
     * Using {@code java.home} guarantees we find it even when the JDK {@code bin/} directory
     * is not on the system {@code PATH} (common on Linux/macOS developer machines where
     * only the {@code java} launcher is symlinked into {@code /usr/bin}).
     */
    private static String keytoolPath() {
        return System.getProperty("java.home")
            + java.io.File.separator + "bin"
            + java.io.File.separator + KEYTOOL_CMD;
    }

    private static void runKeytool(String[] cmd) throws IOException {
        cmd[0] = keytoolPath();
        runProcess("keytool " + cmd[1], cmd, KEYTOOL_TIMEOUT_SECONDS);
    }

    static void runProcess(String operation, String[] cmd, long timeoutSeconds) throws IOException {
        Process process = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start();
        ProcessOutput output = new ProcessOutput();
        Thread outputReader = startOutputCapture(process, output);
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(operation + " interrupted", e);
        }

        if (!finished) {
            terminateProcess(process, operation);
            waitForOutputReader(outputReader, operation);
            throw new IOException(operation + " timed out after " + timeoutSeconds + " seconds"
                + formatProcessOutput(output));
        }

        waitForOutputReader(outputReader, operation);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException(operation + " failed with exit code " + exitCode + formatProcessOutput(output));
        }
    }

    private static Thread startOutputCapture(Process process, ProcessOutput output) {
        Thread outputReader = new Thread(() -> captureProcessOutput(process, output),
            "agentbridge-process-output");
        outputReader.setDaemon(true);
        outputReader.start();
        return outputReader;
    }

    private static void captureProcessOutput(Process process, ProcessOutput output) {
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            LOG.warn("[ChatWebServer] Could not capture process output", e);
        }
    }

    private static void terminateProcess(Process process, String operation) throws IOException {
        process.destroy();
        try {
            if (!process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(operation + " interrupted while terminating timed-out process", e);
        }
    }

    private static void waitForOutputReader(Thread outputReader, String operation) throws IOException {
        try {
            outputReader.join(TimeUnit.SECONDS.toMillis(PROCESS_TERMINATION_TIMEOUT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(operation + " interrupted while collecting process output", e);
        }
        if (outputReader.isAlive()) {
            LOG.warn("[ChatWebServer] Timed out while collecting process output for " + operation);
        }
    }

    private static String formatProcessOutput(ProcessOutput output) {
        String trimmed = output.text().trim();
        if (trimmed.isEmpty()) {
            return ": no process output";
        }
        if (trimmed.length() > MAX_PROCESS_OUTPUT_CHARS) {
            trimmed = trimmed.substring(0, MAX_PROCESS_OUTPUT_CHARS) + "...";
        }
        return ": " + trimmed;
    }

    private static final class ProcessOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        synchronized void write(byte[] buffer, int offset, int length) {
            bytes.write(buffer, offset, length);
        }

        synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8);
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

    private static boolean serverCertCoversAllIps(java.io.File serverKsFile, List<String> requiredIps, String ksPassword) {
        if (requiredIps.isEmpty()) return true;
        try {
            KeyStore ks = KeyStore.getInstance(PKCS12_TYPE);
            try (java.io.FileInputStream fis = new java.io.FileInputStream(serverKsFile)) {
                ks.load(fis, ksPassword.toCharArray());
            }
            java.security.cert.Certificate cert = ks.getCertificate(KT_ALIAS_SERVER);
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
    private static boolean serverCertHasExpectedSubject(java.io.File serverKsFile, String ksPassword) {
        try {
            KeyStore ks = KeyStore.getInstance(PKCS12_TYPE);
            try (java.io.FileInputStream fis = new java.io.FileInputStream(serverKsFile)) {
                ks.load(fis, ksPassword.toCharArray());
            }
            java.security.cert.Certificate cert = ks.getCertificate(KT_ALIAS_SERVER);
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
            exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, "application/x-pem-file");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"agentbridge-ca.pem\"");
            exchange.getResponseHeaders().set(HDR_CACHE_CONTROL, CACHE_NO_CACHE);
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
        exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.getResponseHeaders().set(HDR_CACHE_CONTROL, CACHE_NO_CACHE);
        exchange.sendResponseHeaders(200, html.length);
        exchange.getResponseBody().write(html);
        exchange.close();
    }

    private void handleIconSvg(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] bytes = buildIconSvg().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, "image/svg+xml");
        exchange.getResponseHeaders().set(HDR_CACHE_CONTROL, CACHE_PUBLIC);
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
        exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, "image/png");
        exchange.getResponseHeaders().set(HDR_CACHE_CONTROL, CACHE_PUBLIC);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ── Badge icon (monochrome silhouette for Android status bar) ─────────────

    private static final byte[] BADGE_96_PNG;

    static {
        BADGE_96_PNG = generateBadgePng(96);
    }

    private static byte[] generateBadgePng(int size) {
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = createScaledIconGraphics(img, size);
        try {
            g.setColor(java.awt.Color.WHITE);
            drawAgentBridgeMark(g);
        } finally {
            g.dispose();
        }
        return writePng(img);
    }

    private void handleBadgePng(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, "image/png");
        exchange.getResponseHeaders().set(HDR_CACHE_CONTROL, CACHE_PUBLIC);
        exchange.sendResponseHeaders(200, BADGE_96_PNG.length);
        exchange.getResponseBody().write(BADGE_96_PNG);
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
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
            int arc = size / 5;
            g.setColor(new java.awt.Color(0x1E1F22));
            g.fillRoundRect(0, 0, size, size, arc, arc);
            scaleIconGraphics(g, size);
            g.setColor(new java.awt.Color(0xECEEF2));
            drawAgentBridgeMark(g);
        } finally {
            g.dispose();
        }
        return writePng(img);
    }

    private static java.awt.Graphics2D createScaledIconGraphics(java.awt.image.BufferedImage img, int size) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
        scaleIconGraphics(g, size);
        return g;
    }

    private static void scaleIconGraphics(java.awt.Graphics2D g, int size) {
        double pad = size * 0.10;
        double scale = (size - 2 * pad) / 13.0;
        g.translate(pad, pad);
        g.scale(scale, scale);
    }

    private static void drawAgentBridgeMark(java.awt.Graphics2D g) {
        java.awt.geom.Path2D.Double bolt = new java.awt.geom.Path2D.Double();
        bolt.moveTo(7.925, 0);
        bolt.lineTo(3.907, 6.5);
        bolt.lineTo(6.98, 6.5);
        bolt.lineTo(3.907, 13);
        bolt.lineTo(9.58, 5.318);
        bolt.lineTo(6.389, 5.318);
        bolt.closePath();
        g.fill(bolt);

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
    }

    private static byte[] writePng(java.awt.image.BufferedImage img) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(img, "PNG", baos);
        } catch (IOException ignored) {
            // ImageIO.write to a ByteArrayOutputStream never throws IOException
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

        exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set(HDR_CACHE_CONTROL, CACHE_NO_CACHE);
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set(HDR_ACCESS_CONTROL_ORIGIN, "*");
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Client disconnected
        } finally {
            sseClients.remove(client);
            try {
                exchange.close();
            } catch (Exception ignored) {
                // best-effort cleanup; connection may already be closed
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
        int domLimit = ChatHistorySettings.getInstance(project).getDomMessageLimit();
        StringBuilder sb = new StringBuilder(SEQ_PREFIX).append(seq)
            .append(",\"domMessageLimit\":").append(domLimit)
            .append(",\"events\":[");
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(snapshot.get(i));
        }
        sb.append("],\"info\":").append(buildInfoJson()).append("}");
        sendJson(exchange, sb.toString());
    }

    private void handleCatchUp(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        int fromSeq = parseFromQuery(exchange.getRequestURI().getQuery());
        List<String> snapshot;
        int seq;
        synchronized (this) {
            snapshot = new ArrayList<>(eventLog);
            seq = nextSeq - 1;
        }
        StringBuilder sb = new StringBuilder(SEQ_PREFIX).append(seq).append(",\"events\":[");
        boolean first = true;
        for (String ev : snapshot) {
            if (extractSeq(ev) > fromSeq) {
                if (!first) sb.append(',');
                sb.append(ev);
                first = false;
            }
        }
        sb.append("]}");
        sendJson(exchange, sb.toString());
    }

    private void handleInfo(HttpExchange exchange) throws IOException {
        sendJson(exchange, buildInfoJson());
    }

    private void handleAction(HttpExchange exchange, Consumer<String> handler) throws IOException {
        exchange.getResponseHeaders().set(HDR_ACCESS_CONTROL_ORIGIN, "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", HDR_CONTENT_TYPE);

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
        for (SseClient c : sseClients) {
            if (!c.offer(json)) {
                LOG.info("SSE event dropped for a client — queue full (capacity 300). " +
                    "PWA may show stale or incomplete content.");
            }
        }
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
            exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, contentType);
            exchange.getResponseHeaders().set(HDR_CACHE_CONTROL, CACHE_NO_CACHE);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HDR_CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseHeaders().set(HDR_ACCESS_CONTROL_ORIGIN, "*");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String buildInfoJson() {
        List<String> certIps = new ArrayList<>();
        if (sslKeyStore != null) {
            try {
                java.security.cert.Certificate cert = sslKeyStore.getCertificate(KT_ALIAS_SERVER);
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
        return EventLogCompactor.parseFromQuery(query);
    }

    private static int extractSeq(String json) {
        return EventLogCompactor.extractSeq(json);
    }

    private static String extractFirstStringArg(String js) {
        return EventLogCompactor.extractFirstStringArg(js);
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
        boolean isDark = com.github.catatafishen.agentbridge.psi.PlatformApiCompat.isCurrentThemeDark();
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
            + "  <title>AgentBridge — " + MessageFormatter.INSTANCE.escapeHtml(projectName) + "</title>\n"
            + "  <link rel=\"manifest\" href=\"/manifest.json\">\n"
            + "  <link rel=\"stylesheet\" href=\"/chat.css\">\n"
            + "  <link rel=\"stylesheet\" href=\"/web-app.css\">\n"
            + "  <style>:root { " + cssVars + " }</style>\n"
            + "</head>\n"
            + "<body class=\"" + bodyClass + "\">\n"
            + "  <div id=\"ab-offline\">Connection lost, reconnecting…</div>\n"
            + "  <div id=\"ab-header\">\n"
            + "    <div id=\"ab-title\">AgentBridge — " + MessageFormatter.INSTANCE.escapeHtml(projectName) + "</div>\n"
            + "    <div id=\"ab-model\"></div>\n"
            + "    <div id=\"ab-status\" title=\"Connecting…\"></div>\n"
            + "    <button id=\"ab-menu-btn\" aria-label=\"Menu\" title=\"Menu\">☰</button>\n"
            + "  </div>\n"
            + "  <div id=\"ab-menu\" hidden>\n"
            + "    <div id=\"ab-menu-version\"></div>\n"
            + "    <div id=\"ab-menu-model-section\">\n"
            + "      <label id=\"ab-menu-model-label\">Model</label>\n"
            + "      <select id=\"ab-menu-model\"></select>\n"
            + "    </div>\n"
            + "    <button id=\"ab-menu-disconnect\">✕️ Disconnect ACP</button>\n"
            + "    <div class=\"ab-menu-sep\"></div>\n"
            + "    <button id=\"ab-menu-reload\">\uD83D\uDD04 Hard reload</button>\n"
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
            + "          <select id=\"ab-connect-profile\" aria-label=\"ACP profile\"></select>\n"
            + "          <button id=\"ab-connect-btn\">Connect</button>\n"
            + "          <div id=\"ab-connect-status\"></div>\n"
            + "        </div>\n"
            + "      </div>\n"
            + "    </div>\n"
            + "  </div>\n"
            + "  <div id=\"ab-chat\"><chat-container></chat-container></div>\n"
            + "  <div id=\"ab-footer\">\n"
            + "    <textarea id=\"ab-input\" rows=\"1\" placeholder=\"Message…\" enterkeyhint=\"send\"></textarea>\n"
            + "    <button id=\"ab-send\">" + iconSvg + "<span>Send</span></button>\n"
            + "  </div>\n"
            + "  <script src=\"/chat.bundle.js\"></script>\n"
            + "  <script>window.ICON_SVG = " + escJs(iconSvg) + ";</script>\n"
            + "  <script src=\"/web-app.js\"></script>\n"
            + "</body>\n"
            + "</html>\n";
    }

    private static String escJs(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

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
