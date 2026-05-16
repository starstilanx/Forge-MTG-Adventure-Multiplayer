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
    private static final String DEFAULT_MODEL = "gemini-3.1-flash-lite";
    private static final String DEFAULT_LOCATION = "us-central1";
    private static final String ENDPOINT_TEMPLATE =
        "https://aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent";

    private final String apiKey;
    private final String endpoint;
    private final HttpClient http;

    public GeminiClient() {
        this.apiKey = System.getenv("GEMINI_API_KEY");

        String projectId = System.getProperty("forge.gemini.projectId");
        if (projectId == null || projectId.isEmpty()) {
            projectId = System.getenv("GEMINI_PROJECT_ID");
        }
        if (projectId == null || projectId.isEmpty()) {
            projectId = "gen-lang-client-0610286057";
        }

        String location = System.getenv("GEMINI_LOCATION");
        if (location == null || location.isEmpty()) {
            location = DEFAULT_LOCATION;
        }

        String model = System.getProperty("forge.gemini.model");
        if (model == null || model.isEmpty()) {
            model = System.getenv("GEMINI_MODEL");
        }
        if (model == null || model.isEmpty()) {
            model = DEFAULT_MODEL;
        }

        this.endpoint = String.format(ENDPOINT_TEMPLATE, projectId, location, model);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private static final String LOG_FILE = "gemini.log";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static void log(String section, String content) {
        String timestamp = LocalDateTime.now().format(LOG_TIME);
        String entry = "\n=== " + section + " [" + timestamp + "] ===\n" + content + "\n";
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write(entry);
        } catch (IOException e) {
            Logger.warn("Could not write to gemini.log: {}", e.getMessage());
        }
    }

    public JsonObject ask(String systemPrompt, String userJson) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable not set");
        }

        log("REQUEST", userJson);

        String requestBody = buildRequestBody(systemPrompt, userJson);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(30))
            .build();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject result = extractJsonResponse(response.body());
                log("RESPONSE", result.toString());
                return result;
            }
            if (response.statusCode() == 503 && attempt < MAX_RETRIES) {
                Logger.warn("Gemini API returned 503, retrying ({}/{})", attempt, MAX_RETRIES);
                Thread.sleep(RETRY_DELAY_MS);
            } else {
                Logger.warn("Gemini API returned status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Gemini API error: HTTP " + response.statusCode());
            }
        }
        throw new RuntimeException("Gemini API error: failed after " + MAX_RETRIES + " retries");
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
