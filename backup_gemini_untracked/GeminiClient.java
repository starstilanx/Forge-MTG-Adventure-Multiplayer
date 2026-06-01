package forge.ai.gemini;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.tinylog.Logger;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class GeminiClient {
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String VERTEX_ENDPOINT =
        "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent";

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final String LOG_FILE = "gemini.log";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String apiKey;
    private final String model;
    private final boolean useVertex;
    private final String vertexProject;
    private final String vertexLocation;
    private final HttpClient http;

    public GeminiClient() {
        this.apiKey = System.getenv("GEMINI_API_KEY");

        String m = System.getProperty("forge.gemini.model");
        if (m == null || m.isEmpty()) m = System.getenv("GEMINI_MODEL");
        if (m == null || m.isEmpty()) m = DEFAULT_MODEL;
        this.model = m;

        this.useVertex = Boolean.parseBoolean(System.getProperty("forge.gemini.useVertex", "false"));

        String proj = System.getProperty("forge.gemini.vertexProject");
        if (proj == null || proj.isEmpty()) proj = System.getenv("GOOGLE_CLOUD_PROJECT");
        this.vertexProject = proj != null ? proj : "";

        String loc = System.getProperty("forge.gemini.vertexLocation");
        if (loc == null || loc.isEmpty()) loc = "us-central1";
        this.vertexLocation = loc;

        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static void log(String section, String content) {
        String timestamp = LocalDateTime.now().format(LOG_TIME);
        String entry = "\n=== " + section + " [" + timestamp + "] ===\n" + content + "\n";
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write(entry);
        } catch (IOException e) {
            Logger.warn("Could not write to gemini.log: {}", e.getMessage());
        }
    }

    private String getVertexAccessToken() {
        String token = System.getenv("VERTEX_ACCESS_TOKEN");
        if (token != null && !token.isEmpty()) return token;
        try {
            ProcessBuilder pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !result.isEmpty()) return result;
        } catch (Exception e) {
            Logger.warn("gcloud auth failed: {}", e.getMessage());
        }
        throw new IllegalStateException(
            "No Vertex AI access token. Set VERTEX_ACCESS_TOKEN env var or authenticate via gcloud.");
    }

    public JsonObject ask(String systemPrompt, String userJson) throws Exception {
        log("REQUEST", userJson);

        String requestBody = buildRequestBody(systemPrompt, userJson);

        HttpRequest request;
        if (useVertex) {
            if (vertexProject.isEmpty()) {
                throw new IllegalStateException(
                    "Vertex AI project not set. Set GOOGLE_CLOUD_PROJECT env var or configure in settings.");
            }
            String token = getVertexAccessToken();
            String url = String.format(VERTEX_ENDPOINT, vertexLocation, vertexProject, vertexLocation, model);
            request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();
        } else {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("GEMINI_API_KEY environment variable not set");
            }
            String url = String.format(GEMINI_ENDPOINT, model, apiKey);
            request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject result = extractJsonResponse(response.body());
                log("RESPONSE", result.toString());
                return result;
            }
            if (response.statusCode() == 503 && attempt < MAX_RETRIES) {
                Logger.warn("API returned 503, retrying ({}/{})", attempt, MAX_RETRIES);
                Thread.sleep(RETRY_DELAY_MS);
            } else {
                Logger.warn("API returned status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("API error: HTTP " + response.statusCode());
            }
        }
        throw new RuntimeException("API error: failed after " + MAX_RETRIES + " retries");
    }

    private String buildRequestBody(String systemPrompt, String userJson) {
        JsonObject body = new JsonObject();

        JsonObject sysInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", systemPrompt);
        sysParts.add(sysPart);
        sysInstruction.add("parts", sysParts);
        body.add("system_instruction", sysInstruction);

        JsonArray contents = new JsonArray();
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", userJson);
        userParts.add(userPart);
        userContent.add("parts", userParts);
        contents.add(userContent);
        body.add("contents", contents);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("responseMimeType", "application/json");
        body.add("generationConfig", genConfig);

        return body.toString();
    }

    private JsonObject extractJsonResponse(String responseBody) {
        JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
        String text = response
            .getAsJsonArray("candidates")
            .get(0).getAsJsonObject()
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
