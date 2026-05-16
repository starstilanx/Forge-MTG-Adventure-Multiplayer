package forge.ai.gemini;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.tinylog.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GeminiCommentator {

    private static final String MODEL = "gemini-2.5-flash";
    private static final String DEFAULT_LOCATION = "us-central1";
    private static final String ENDPOINT_TEMPLATE =
        "https://aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent";

    private static final String SYSTEM_PROMPT =
        "You are playing Magic: The Gathering. In 1-2 short sentences, comment on the current " +
        "board state in character as a competitive but sportsmanlike player. Be natural, " +
        "conversational, and occasionally witty. Do not reference JSON or game mechanics jargon " +
        "— speak like a real player would at the table. Plain text only, no formatting.";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final String apiKey;
    private final String endpoint;

    public GeminiCommentator() {
        this.apiKey = System.getenv("GEMINI_API_KEY");

        String projectId = System.getenv("GEMINI_PROJECT_ID");
        if (projectId == null || projectId.isEmpty()) {
            projectId = "gen-lang-client-0610286057";
        }

        String location = System.getenv("GEMINI_LOCATION");
        if (location == null || location.isEmpty()) {
            location = DEFAULT_LOCATION;
        }

        this.endpoint = String.format(ENDPOINT_TEMPLATE, projectId, location, MODEL);
    }

    public String getCommentary(String gameStateJson) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) return null;

        JsonObject body = new JsonObject();

        JsonObject sys = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", SYSTEM_PROMPT);
        sysParts.add(sysPart);
        sys.add("parts", sysParts);
        body.add("system_instruction", sys);

        JsonArray contents = new JsonArray();
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", gameStateJson);
        parts.add(part);
        userContent.add("parts", parts);
        contents.add(userContent);
        body.add("contents", contents);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            Logger.warn("GeminiCommentator: API returned HTTP {}", response.statusCode());
            return null;
        }

        return JsonParser.parseString(response.body())
            .getAsJsonObject()
            .getAsJsonArray("candidates")
            .get(0).getAsJsonObject()
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).getAsJsonObject()
            .get("text").getAsString()
            .trim();
    }
}
