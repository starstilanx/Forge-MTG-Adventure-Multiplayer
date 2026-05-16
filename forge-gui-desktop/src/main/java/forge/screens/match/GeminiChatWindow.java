package forge.screens.match;

import forge.ai.gemini.GeminiChatBus;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class GeminiChatWindow {

    private static final Color BG       = new Color(20, 20, 28);
    private static final Color BUBBLE_AI = new Color(45, 55, 72);
    private static final Color TEXT_AI   = new Color(220, 220, 230);
    private static final Color NAME_AI   = new Color(140, 180, 255);
    private static final Font  FONT_NAME = new Font("SansSerif", Font.BOLD, 12);
    private static final Font  FONT_MSG  = new Font("SansSerif", Font.PLAIN, 13);

    private JFrame frame;
    private JPanel messagePanel;
    private JScrollPane scrollPane;

    private static GeminiChatWindow instance;

    public static GeminiChatWindow getInstance() {
        if (instance == null) {
            instance = new GeminiChatWindow();
        }
        return instance;
    }

    private GeminiChatWindow() {
        GeminiChatBus.setListener(this::postMessage);
    }

    private synchronized void ensureWindowCreated() {
        if (frame != null) return;

        frame = new JFrame("AI Opponent");
        frame.setUndecorated(false);
        frame.setAlwaysOnTop(false);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setSize(300, 500);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(screen.width - 320, (screen.height - 500) / 2);

        messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBackground(BG);
        messagePanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        scrollPane = new JScrollPane(messagePanel);
        scrollPane.setBackground(BG);
        scrollPane.getViewport().setBackground(BG);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(BG);
        frame.add(scrollPane, BorderLayout.CENTER);
    }

    public void postMessage(String senderName, String message) {
        SwingUtilities.invokeLater(() -> {
            ensureWindowCreated();

            JPanel bubble = new JPanel();
            bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
            bubble.setBackground(BUBBLE_AI);
            bubble.setBorder(new EmptyBorder(6, 10, 6, 10));
            bubble.setMaximumSize(new Dimension(260, Integer.MAX_VALUE));
            bubble.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel nameLabel = new JLabel(senderName);
            nameLabel.setFont(FONT_NAME);
            nameLabel.setForeground(NAME_AI);
            nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            JTextArea msgArea = new JTextArea(message);
            msgArea.setFont(FONT_MSG);
            msgArea.setForeground(TEXT_AI);
            msgArea.setBackground(BUBBLE_AI);
            msgArea.setEditable(false);
            msgArea.setLineWrap(true);
            msgArea.setWrapStyleWord(true);
            msgArea.setMaximumSize(new Dimension(240, Integer.MAX_VALUE));
            msgArea.setAlignmentX(Component.LEFT_ALIGNMENT);
            msgArea.setBorder(null);
            msgArea.setOpaque(true);

            bubble.add(nameLabel);
            bubble.add(Box.createVerticalStrut(3));
            bubble.add(msgArea);

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setBackground(BG);
            row.setMaximumSize(new Dimension(280, Integer.MAX_VALUE));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(bubble);

            messagePanel.add(row);
            messagePanel.add(Box.createVerticalStrut(8));
            messagePanel.revalidate();

            if (!frame.isVisible()) {
                frame.setVisible(true);
            }

            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        });
    }

    public void reset() {
        if (frame == null) return;
        SwingUtilities.invokeLater(() -> {
            messagePanel.removeAll();
            messagePanel.revalidate();
            messagePanel.repaint();
            frame.setVisible(false);
        });
    }
}
