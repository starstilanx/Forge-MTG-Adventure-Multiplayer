package forge.ai.gemini;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.tinylog.Logger;

import java.io.FileWriter;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class GeminiClient {
    private static final String DEFAULT_MODEL = "gemini-2.0-flash-lite";
    private static final String DEFAULT_LOCATION = "us-central1";

    private static final String GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final boolean useVertex;
    private final boolean isMaas;   // true for MaaS (OpenAI-compat) models
    private final String maasModelId; // e.g. "zai-org/glm-4.7-maas"
    private final String apiKey;
    private final String endpoint;
    private final HttpClient http;

    public GeminiClient() {
        String model = System.getProperty("forge.gemini.model");
        if (model == null || model.isEmpty()) model = System.getenv("GEMINI_MODEL");
        if (model == null || model.isEmpty()) model = DEFAULT_MODEL;

        useVertex = Boolean.parseBoolean(System.getProperty("forge.gemini.useVertex", "false"));
        isMaas = useVertex && model.endsWith("-maas");
        maasModelId = isMaas ? maasModelId(model) : null;

        String key = System.getenv("GEMINI_API_KEY");
        this.apiKey = key;

        if (useVertex) {
            String project = System.getProperty("forge.gemini.vertexProject");
            if (project == null || project.isEmpty()) project = System.getenv("VERTEX_PROJECT");
            if (project == null || project.isEmpty()) project = System.getenv("GEMINI_PROJECT_ID");
            if (project == null || project.isEmpty()) project = "gen-lang-client-0610286057";

            String location = System.getProperty("forge.gemini.vertexLocation");
            if (location == null || location.isEmpty()) location = System.getenv("VERTEX_LOCATION");
            if (location == null || location.isEmpty()) location = DEFAULT_LOCATION;

            // "global" uses no host prefix; "eu" uses "eu-"; all others use "{location}-"
            String hostPrefix;
            switch (location) {
                case "global": hostPrefix = ""; break;
                case "eu":     hostPrefix = "eu-"; break;
                default:       hostPrefix = location + "-"; break;
            }
            String host = "https://" + hostPrefix + "aiplatform.googleapis.com";

            if (isMaas) {
                // MaaS models (non-Gemini) use the OpenAI-compatible chat completions endpoint
                this.endpoint = host + String.format(
                    "/v1/projects/%s/locations/%s/endpoints/openapi/chat/completions",
                    project, location);
            } else {
                this.endpoint = host + String.format(
                    "/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                    project, location, model);
            }
        } else {
            this.endpoint = String.format(GEMINI_ENDPOINT, model, key != null ? key : "");
        }

        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Maps the display model name used in settings to the API model ID required by the
     * MaaS OpenAI-compatible endpoint (org-prefix + canonical dot-version name).
     */
    private static String maasModelId(String model) {
        switch (model) {
            case "glm-4-7-maas": return "zai-org/glm-4.7-maas";
            case "glm-5-maas":   return "zai-org/glm-5-maas";
            default:
                if (model.startsWith("glm-"))       return "zai-org/" + model;
                if (model.startsWith("deepseek-"))  return "deepseek/" + model;
                if (model.startsWith("qwen"))       return "qwen/" + model;
                if (model.startsWith("minimax-"))   return "minimax/" + model;
                if (model.startsWith("gpt-oss-"))   return "microsoft/" + model;
                return model;
        }
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

    private String getVertexBearerToken() {
        String token = System.getenv("VERTEX_ACCESS_TOKEN");
        if (token != null && !token.isEmpty()) return token;

        try {
            Process proc = new ProcessBuilder("gcloud", "auth", "print-access-token")
                .redirectErrorStream(true)
                .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty() && !line.contains("ERROR")) {
                    return line.trim();
                }
            }
        } catch (IOException e) {
            Logger.warn("Could not get Vertex access token via gcloud: {}", e.getMessage());
        }
        return null;
    }

    public JsonObject ask(String systemPrompt, String userJson) throws Exception {
        if (!useVertex && (apiKey == null || apiKey.isEmpty())) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable not set");
        }

        log("REQUEST", userJson);

        String requestBody = isMaas
            ? buildMaasRequestBody(systemPrompt, userJson)
            : buildGeminiRequestBody(systemPrompt, userJson);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(30));

        if (useVertex) {
            if (apiKey != null && !apiKey.isEmpty()) {
                reqBuilder.header("x-goog-api-key", apiKey);
            } else {
                String token = getVertexBearerToken();
                if (token != null) {
                    reqBuilder.header("Authorization", "Bearer " + token);
                } else {
                    throw new IllegalStateException("No Vertex AI credentials. Set GEMINI_API_KEY (Agent Platform API key) or VERTEX_ACCESS_TOKEN.");
                }
            }
        }

        HttpRequest request = reqBuilder.build();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject result = isMaas
                    ? extractMaasJsonResponse(response.body())
                    : extractGeminiJsonResponse(response.body());
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

    // --- Gemini generateContent format ---

    private String buildGeminiRequestBody(String systemPrompt, String userJson) {
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

    private JsonObject extractGeminiJsonResponse(String responseBody) {
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

    // --- MaaS OpenAI-compatible chat completions format ---

    private String buildMaasRequestBody(String systemPrompt, String userJson) {
        JsonObject body = new JsonObject();
        body.addProperty("model", maasModelId);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userJson);
        messages.add(userMsg);

        body.add("messages", messages);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        return body.toString();
    }

    private JsonObject extractMaasJsonResponse(String responseBody) {
        JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
        String text = response
            .getAsJsonArray("choices")
            .get(0).getAsJsonObject()
            .getAsJsonObject("message")
            .get("content").getAsString();
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
