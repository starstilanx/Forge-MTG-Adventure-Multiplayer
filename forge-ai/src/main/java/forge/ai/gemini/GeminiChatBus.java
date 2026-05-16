package forge.ai.gemini;

public class GeminiChatBus {

    public interface ChatListener {
        void onMessage(String senderName, String message);
    }

    private static volatile ChatListener listener;

    public static void setListener(ChatListener l) {
        listener = l;
    }

    public static void post(String senderName, String message) {
        ChatListener l = listener;
        if (l != null) {
            l.onMessage(senderName, message);
        }
    }
}
