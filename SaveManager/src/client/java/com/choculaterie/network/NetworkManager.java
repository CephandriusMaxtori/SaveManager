package com.choculaterie.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public class NetworkManager {
    private static final String BASE_URL = "https://api.choculaterie.com";
    private static final String API_BASE_PATH = "/api/SaveManagerAPI";
    private static final String API_KEY_HEADER = "X-Save-Key";

    private static final HttpClient INSECURE_CLIENT;
    private static final SSLSocketFactory INSECURE_SSLSF;
    private static final HostnameVerifier INSECURE_HV;

    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trustAllCerts, new SecureRandom());
            try { SSLContext.setDefault(ssl); } catch (Throwable ignored) {}
            INSECURE_CLIENT = HttpClient.newBuilder()
                    .sslContext(ssl)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            INSECURE_SSLSF = ssl.getSocketFactory();
            INSECURE_HV = (hostname, session) -> true;
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(INSECURE_SSLSF);
                HttpsURLConnection.setDefaultHostnameVerifier(INSECURE_HV);
            } catch (Throwable ignored) {}
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize HttpClient with custom SSL context", e);
        }
    }

    private final HttpClient httpClient;
    private String apiKey;

    public NetworkManager() {
        this.httpClient = INSECURE_CLIENT;
    }

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiKey() { return apiKey; }


    public CompletableFuture<JsonObject> initiateOAuthFlow(String clientName) {
        String body = "\"" + (clientName != null ? clientName : "SaveManager Mod") + "\"";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/flow/initiate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    public CompletableFuture<JsonObject> getOAuthFlowStatus(String flowId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/flow/status/" + flowId))
                .GET()
                .build();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    public String getOAuthAuthorizeUrl(String flowId) {
        return "https://choculaterie.com/save-manager/authorize/" + flowId;
    }

    public CompletableFuture<JsonObject> cancelOAuthFlow(String flowId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/flow/cancel/" + flowId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    public CompletableFuture<JsonObject> getQuotaInfo() {
        validateApiKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/quota-info"))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    public CompletableFuture<JsonObject> listWorldSaves() {
        validateApiKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/list"))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    public CompletableFuture<JsonObject> uploadWorldSave(String worldName, Path zipFilePath, BiConsumer<Long, Long> progressCallback) throws IOException {
        validateApiKey();

        String safeWorldName = (worldName == null || worldName.isBlank()) ? "world" : worldName;
        String fileName = safeWorldName + ".zip";
        String boundary = "----savemanager-" + UUID.randomUUID();
        final String CRLF = "\r\n";

        long fileSize = Files.size(zipFilePath);
        String uploadBase = "https://upload.choculaterie.com";

        String partWorldName = "--" + boundary + CRLF +
                "Content-Disposition: form-data; name=\"WorldName\"" + CRLF + CRLF +
                safeWorldName + CRLF;

        String partFileHeader = "--" + boundary + CRLF +
                "Content-Disposition: form-data; name=\"SaveFile\"; filename=\"" + fileName + "\"" + CRLF +
                "Content-Type: application/zip" + CRLF + CRLF;

        byte[] preamble = (partWorldName + partFileHeader).getBytes(StandardCharsets.UTF_8);
        byte[] closing = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);
        long contentLength = preamble.length + fileSize + closing.length;

        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = URI.create(uploadBase + API_BASE_PATH + "/upload").toURL();
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection https) {
                    try {
                        https.setSSLSocketFactory(INSECURE_SSLSF);
                        https.setHostnameVerifier(INSECURE_HV);
                    } catch (Throwable ignored) {}
                }

                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty(API_KEY_HEADER, apiKey);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setRequestProperty("User-Agent", "SaveManager/1.0");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(600_000);
                conn.setFixedLengthStreamingMode(contentLength);

                try (OutputStream rawOut = conn.getOutputStream();
                     BufferedOutputStream out = new BufferedOutputStream(rawOut, 128 * 1024);
                     InputStream in = Files.newInputStream(zipFilePath)) {

                    out.write(preamble);

                    byte[] buf = new byte[128 * 1024];
                    long sentFileBytes = 0L;
                    if (progressCallback != null) progressCallback.accept(0L, fileSize);

                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                        sentFileBytes += r;
                        if (progressCallback != null) progressCallback.accept(sentFileBytes, fileSize);
                        if (sentFileBytes % (1024 * 1024) == 0) out.flush();
                    }

                    out.write(closing);
                    out.flush();

                    if (progressCallback != null) progressCallback.accept(fileSize, fileSize);
                }

                int status = conn.getResponseCode();
                InputStream respStream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
                String body = "";
                if (respStream != null) {
                    try (respStream) {
                        body = new String(respStream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }

                if (status >= 400) {
                    String errorMsg;
                    if (status == 413) {
                        errorMsg = "File too large: Server's reverse proxy is blocking uploads.";
                    } else if (status == 401 || status == 403) {
                        errorMsg = "Authentication failed: Please check your API key.";
                    } else if (status == 429) {
                        errorMsg = "Rate limit exceeded. Please try again later.";
                    } else {
                        String cleanBody = body.replaceAll("<[^>]+>", "").trim();
                        if (cleanBody.length() > 200) cleanBody = cleanBody.substring(0, 200) + "...";
                        errorMsg = "HTTP " + status + (cleanBody.isEmpty() ? "" : ": " + cleanBody);
                    }
                    throw new RuntimeException(errorMsg);
                }

                if (body.isBlank()) {
                    JsonObject result = new JsonObject();
                    result.addProperty("status", "success");
                    return result;
                }

                try {
                    return JsonParser.parseString(body).getAsJsonObject();
                } catch (Exception parseEx) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("raw", body);
                    obj.addProperty("status", "success");
                    return obj;
                }
            } catch (IOException ioEx) {
                String errorDetails = "IO Error: " + ioEx.getMessage();
                if (conn != null) {
                    try {
                        int status = conn.getResponseCode();
                        InputStream errStream = conn.getErrorStream();
                        if (errStream != null) {
                            String errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                            errorDetails = "HTTP " + status + ": " + errBody;
                        }
                    } catch (Exception ignored) {}
                }
                throw new RuntimeException("Upload failed: " + errorDetails, ioEx);
            } catch (Exception e) {
                throw new RuntimeException("Upload failed: " + e.getMessage(), e);
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    public CompletableFuture<java.util.List<String>> listWorldSaveNames() {
        validateApiKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/names"))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new RuntimeException("Request failed: " + response.statusCode() + " - " + response.body());
                    }
                    String body = response.body();
                    java.util.Optional<String> ct = response.headers().firstValue("Content-Type");
                    if (ct.map(s -> s.contains("text/html")).orElse(false) || body.startsWith("<!DOCTYPE")) {
                        throw new RuntimeException("Unexpected HTML response from server.");
                    }
                    com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(body).getAsJsonArray();
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement e : arr) {
                        if (e != null && e.isJsonPrimitive()) out.add(e.getAsString());
                    }
                    return out;
                });
    }

    public CompletableFuture<Path> downloadWorldSave(String saveId, Path destinationDirectory, BiConsumer<Long, Long> progressCallback) {
        validateApiKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/download/" + saveId))
                .header(API_KEY_HEADER, apiKey)
                .header("Accept-Encoding", "identity")
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenCompose(response -> CompletableFuture.supplyAsync(() -> {
                    int status = response.statusCode();
                    if (status >= 400) {
                        try (InputStream err = response.body()) {
                            String body = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                            throw new RuntimeException("Request failed: " + status + " - " + body);
                        } catch (IOException e) {
                            throw new RuntimeException("Request failed: " + status, e);
                        }
                    }

                    long total = firstLongHeader(response, "Content-Length", "X-Content-Length", "X-File-Size", "X-Total-Length").orElse(-1L);
                    if (progressCallback != null) {
                        try { progressCallback.accept(0L, total); } catch (Throwable ignored) {}
                    }

                    String fileName = extractFileNameFromResponse(response);
                    try {
                        Files.createDirectories(destinationDirectory);
                        Path temp = Files.createTempFile(destinationDirectory, "download-", ".tmp");

                        long downloaded = 0L;
                        try (InputStream in = response.body();
                             OutputStream out = Files.newOutputStream(temp)) {
                            byte[] buffer = new byte[256 * 1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                                downloaded += read;
                                if (progressCallback != null) {
                                    try { progressCallback.accept(downloaded, total); } catch (Throwable ignored) {}
                                }
                            }
                            out.flush();
                        }

                        Path finalPath = destinationDirectory.resolve(fileName);
                        try {
                            Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } catch (Exception e) {
                            Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING);
                        }

                        long finalSize = Files.size(finalPath);
                        if (progressCallback != null) {
                            long finalTotal = (total > 0) ? total : finalSize;
                            try { progressCallback.accept(finalSize, finalTotal); } catch (Throwable ignored) {}
                        }
                        return finalPath;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
    }

    public CompletableFuture<JsonObject> deleteWorldSave(String saveId) {
        validateApiKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + API_BASE_PATH + "/delete/" + saveId))
                .header(API_KEY_HEADER, apiKey)
                .DELETE()
                .build();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    private String extractFileNameFromResponse(HttpResponse<?> response) {
        String def = "world_save.zip";
        return response.headers()
                .firstValue("Content-Disposition")
                .map(header -> {
                    String lower = header.toLowerCase(java.util.Locale.ROOT);
                    int idx = lower.indexOf("filename=");
                    if (idx >= 0) {
                        String raw = header.substring(idx + 9).trim();
                        if (raw.startsWith("\"")) {
                            int end = raw.indexOf('"', 1);
                            if (end > 1) return raw.substring(1, end);
                        }
                        int semi = raw.indexOf(';');
                        String v = (semi >= 0 ? raw.substring(0, semi) : raw).replace("\"", "").trim();
                        if (!v.isEmpty()) return v;
                    }
                    return def;
                }).orElse(def);
    }

    private Optional<Long> firstLongHeader(HttpResponse<?> response, String... names) {
        for (String n : names) {
            Optional<String> v = response.headers().firstValue(n);
            if (v.isPresent()) {
                try { return Optional.of(Long.parseLong(v.get().trim())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return Optional.empty();
    }

    private void validateApiKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("API key is not set");
        }
    }

    private JsonObject handleJsonResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Request failed: " + response.statusCode() + " - " + response.body());
        }
        Optional<String> ct = response.headers().firstValue("Content-Type");
        String body = response.body();
        if (ct.map(s -> s.contains("text/html")).orElse(false) || body.startsWith("<!DOCTYPE")) {
            throw new RuntimeException("Unexpected HTML response from server.");
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

}
