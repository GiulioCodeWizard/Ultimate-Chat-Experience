/**
 * Giulio Dajani
 * 02/04/2025
 */

package myapp.chat;

import java.io.*;
import java.awt.*;
import java.net.*;
import java.util.*;
import javax.swing.*;
import java.util.List;
import java.awt.event.*;
import javax.swing.Timer;
import javax.swing.text.*;
import javax.sound.sampled.*;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.border.EmptyBorder;
import java.time.format.DateTimeFormatter;


public class ChatClient {
    private String id; // Unique identifier for the client
    private Socket socket; // Network socket to communicate with the server
    private BufferedReader reader; // Reader for receiving messages from the server
    private static PrintWriter writer; // Writer for sending messages to the server
    private JFrame frame; // GUI window
    private static JTextArea chatArea; // Text area for displaying chat messages
    private JTextField inputField;  // Input field for user messages
    private static final Logger logger = Logger.getLogger(ChatClient.class.getName()); // Catch exception errors
    private HashMap<String, ChatWindow> privateChats; // Stores active private chat windows
    private JPopupMenu reactionMenu; // Right-click popup menu for reactions
    private String lastSelectedMessage = null;
    private DefaultListModel<String> userListModel;
    private final Map<Integer, String> messages = new HashMap<>();
    private final Map<String, File> sentFiles = new HashMap<>();
    private File selectedFile;
    private TargetDataLine microphone;
    private File voiceMessageFile;
    private boolean isRecording = false;
    private long voiceButtonPressTime;


    public ChatClient(String id, String serverIP, int port) {
        try {
            socket = new Socket(serverIP, port); // Connect to the server
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));  // Read incoming messages
            writer = new PrintWriter(socket.getOutputStream(), true); // Send messages
            privateChats = new HashMap<>(); // Initialize chat windows storage

            // Ensure the ID is unique before proceeding
            while (true) {
                writer.println(id); // Send ID to server
                String serverResponse = reader.readLine();
                if ("ID_EXISTS".equals(serverResponse)) {
                	// Ask user for a new ID if the entered one already exists
                    id = JOptionPane.showInputDialog("ID already in use. Enter a different ID:");
                    if (id == null || id.trim().isEmpty()) {
                        JOptionPane.showMessageDialog(null, "ID cannot be empty. Try again.",
                                "Invalid ID", JOptionPane.ERROR_MESSAGE);

                        while (true) {
                            id = JOptionPane.showInputDialog("Enter your ID:");

                            if (id == null) {
                                // User clicked 'Cancel' - exit gracefully
                                JOptionPane.showMessageDialog(null, "Client setup canceled.",
                                        "Exit", JOptionPane.INFORMATION_MESSAGE);
                                System.exit(0);
                            }

                            id = id.trim(); // Remove spaces

                            if (!id.isEmpty() && id.matches("[A-Za-z0-9_]+")) { // Allow letters, numbers,
                                // and underscores
                                break; // Valid ID
                            } else {
                                JOptionPane.showMessageDialog(null, "Invalid ID. Only " +
                                        "letters, numbers, and underscores are allowed.", "Error",
                                        JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    }
                } else if ("ID_ACCEPTED".equals(serverResponse)) {
                    this.id = id; // Assign the accepted ID
                    break; // Exit loop when a unique ID is confirmed
                }
            }

            frame = new JFrame("Chat Client - " + id); // Set the chat window title to include the user ID

            String serverResponse = reader.readLine();
            if (serverResponse != null && serverResponse.equals("COORDINATOR")) {
            	// Notify the user if they are assigned as the coordinator
                JOptionPane.showMessageDialog(null, "You are the coordinator.",
                        "Coordinator Assigned", JOptionPane.INFORMATION_MESSAGE);
            }

            buildGUI(); // Build the graphical user interface
            setupMessageContextMenu(); // Set up the context menu for messages
            new MessageReceiver().start(); // Start a thread to listen for incoming messages
        } catch (IOException e) {
            logger.log(Level.SEVERE, "An error occurred while connecting to the server", e);
        }
    }

    public static class TopLeftAlignedTextField extends JTextPane {

        public TopLeftAlignedTextField(String text) {
            super();
            setMargin(new Insets(5, 5, 5, 5)); // Set margin if needed

            // Set the alignment to top-left
            setEditorKit(new TopLeftEditorKit());

            // Initialize with the given text
            setText(text);
        }

        @Override
        public void setText(String t) {
            super.setText(t);
            repaint();
        }

        @Override
        public void setFont(Font f) {
            super.setFont(f);
            repaint();
        }

        @Override
        public void setMargin(Insets m) {
            super.setMargin(m);
            repaint();
        }

        // Custom EditorKit to align text to the top-left
        static class TopLeftEditorKit extends StyledEditorKit {
            @Override
            public ViewFactory getViewFactory() {
                return new StyledViewFactory();
            }

            static class StyledViewFactory implements ViewFactory {
                @Override
                public View create(Element elem) {
                    return new LabelView(elem) {
                        @Override
                        public float getAlignment(int axis) {
                            return 0;
                        }
                    };
                }
            }
        }
    }

    private void buildGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "An error occurred while building the graphical user interface (GUI)", e);
        }

        /* ===== Main Application Window ===== */
        frame = new JFrame("Chat Client - " + id);
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        /* ===== Chat Area Setup ===== */
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setBackground(new Color(240, 248, 255));
        chatArea.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5));
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);

        /* ===== Font Area Setup ===== */
        String os = System.getProperty("os.name").toLowerCase();
        Font emojiFont;
        if (os.contains("win")) {
            emojiFont = new Font("Segoe UI Emoji", Font.PLAIN, 14);
        } else if (os.contains("mac")) {
            emojiFont = new Font("Apple Color Emoji", Font.PLAIN, 14);
        } else {
            emojiFont = new Font("Noto Color Emoji", Font.PLAIN, 14);
        }

        // Wrap the chat area in a scroll pane so that messages can be scrolled
        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));

        /* ===== Input Panel (Bottom Panel) ===== */
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // ===== Inner Input Field Setup (For Typing) =====
        inputField = new JTextField();
        inputField.setFont(emojiFont);
        inputField.setPreferredSize(new Dimension(350, 35));
        inputField.setBorder(null);
        inputField.setText("Type a message");
        inputField.setForeground(Color.GRAY);

        // ===== Icons Setup =====
        JButton emojiButton = createIconButton("😀");
        JButton attachButton = createIconButton("📎");
        JButton voiceMessageButton = createIconButton("🎤");

        // ===== Input Panel (With Icons and Message Field) =====
        JPanel inputContainer = new JPanel(new BorderLayout());
        inputContainer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(3, 5, 3, 5)));
        inputContainer.setBackground(Color.WHITE);

        // ===== Emoji Picker (Popup) =====
        WhatsAppEmojiPicker emojiPicker = new WhatsAppEmojiPicker(inputField, emojiButton);
        JPopupMenu emojiPopup = emojiPicker.getJPopupMenu();

        // Show emoji picker when emoji button is clicked
        emojiButton.addActionListener(_ -> emojiPopup.show(emojiButton, 0, emojiButton.getHeight()));

        // Left Panel (Emoji & Attach)
        JPanel leftIcons = new JPanel(new GridBagLayout());
        leftIcons.setOpaque(true);
        leftIcons.setBackground(Color.WHITE);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(5, 5, 0, 5);

        gbc.gridx = 0;  // Emoji
        leftIcons.add(emojiButton, gbc);

        gbc.gridx = 1;  // Attachment
        leftIcons.add(attachButton, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(7, 1));
        leftIcons.add(spacer, gbc);

        // Right Panel (Voice Message Icon)
        JPanel rightIcons = new JPanel(new GridBagLayout());
        rightIcons.setOpaque(false);
        rightIcons.add(voiceMessageButton, gbc);

        // Add components inside the "input field" container
        inputContainer.add(leftIcons, BorderLayout.WEST);
        inputContainer.add(inputField, BorderLayout.CENTER);
        inputContainer.add(rightIcons, BorderLayout.EAST);

        inputPanel.add(inputContainer, BorderLayout.CENTER);

        /* ===== Top Button Panel ===== */
        JButton userInfo = new JButton("        User  ID        ");
        JButton updateUserInfo = new JButton("        Update         ");
        JButton quitButton = new JButton("Quit");
        JButton requestMemberButton = new JButton("Request Members");
        JButton rightUserInfo = new JButton("                 INFO                 ");
        JButton userStatus = new JButton("        Status          ");
        JButton history = new JButton("        History         ");

        // Style the User Info button
        userInfo.setBackground(new Color(61, 64, 135));
        userInfo.setForeground(Color.BLACK);
        userInfo.setFont(new Font("Arial", Font.BOLD, 14));
        userInfo.setEnabled(false);
        userInfo.setBorderPainted(false);
        userInfo.setFocusPainted(false);
        userInfo.setContentAreaFilled(false);
        userInfo.setOpaque(false);

        //Style the Send button
        JButton sendButton = new JButton("Send");
        sendButton.setFont(new Font("Arial", Font.BOLD, 14));
        sendButton.setBackground(new Color(50, 205, 50));
        sendButton.setForeground(Color.BLACK);
        inputPanel.add(sendButton, BorderLayout.EAST);

        // Style the Update User Info button
        updateUserInfo.setBackground(new Color(78, 156, 110));
        updateUserInfo.setForeground(Color.BLACK);
        updateUserInfo.setFont(new Font("Arial", Font.BOLD, 14));

        // Style the Quit button
        quitButton.setBackground(new Color(220, 20, 60));
        quitButton.setForeground(Color.BLACK);
        quitButton.setFont(new Font("Arial", Font.BOLD, 14));

        // Style the Request Member button
        requestMemberButton.setBackground(new Color(30, 144, 255));
        requestMemberButton.setForeground(Color.BLACK);
        requestMemberButton.setFont(new Font("Arial", Font.BOLD, 14));

        // Style the Right User Info button
        rightUserInfo.setBackground(new Color(166, 115, 38));
        rightUserInfo.setForeground(Color.BLACK);
        rightUserInfo.setFont(new Font("Arial", Font.BOLD, 14));

        // Style the User Status button
        userStatus.setBackground(new Color(15, 207, 178));
        userStatus.setForeground(Color.BLACK);
        userStatus.setFont(new Font("Arial", Font.BOLD, 14));
        userStatus.setEnabled(false);
        userStatus.setBorderPainted(false);
        userStatus.setFocusPainted(false);
        userStatus.setContentAreaFilled(false);
        userStatus.setOpaque(false);

        // Style the History button
        history.setBackground(new Color(166, 171, 255));
        history.setForeground(Color.BLACK);
        history.setFont(new Font("Arial", Font.BOLD, 14));

        /* ===== Top Button Panel ===== */
        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        buttonPanel.add(requestMemberButton);
        buttonPanel.add(quitButton);

        /* ===== User Info and Status Panel (Left Side) ===== */
        JPanel userInfoButtonPanel = new JPanel();
        userInfoButtonPanel.setLayout(new BoxLayout(userInfoButtonPanel, BoxLayout.Y_AXIS));
        userInfoButtonPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 5));

        userInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        userInfoButtonPanel.add(userInfo);
        userInfoButtonPanel.add(Box.createVerticalStrut(4));

        JPanel userIdPanel = new JPanel();
        userIdPanel.setLayout(new BoxLayout(userIdPanel, BoxLayout.X_AXIS));

        TopLeftAlignedTextField userIdField = getUserIdField();

        userIdPanel.add(userIdField);
        userIdPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        userInfoButtonPanel.add(userIdPanel);
        userInfoButtonPanel.add(Box.createVerticalStrut(4));

        updateUserInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        userInfoButtonPanel.add(updateUserInfo);

        userStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        userInfoButtonPanel.add(userStatus);
        userInfoButtonPanel.add(Box.createVerticalStrut(24));

        userInfoButtonPanel.setLayout(new BoxLayout(userInfoButtonPanel, BoxLayout.Y_AXIS));

        /* ===== User Status List Panel (Active Members List) ===== */
        JPanel userStatusPanel = new JPanel();
        userStatusPanel.setLayout(new BoxLayout(userStatusPanel, BoxLayout.Y_AXIS));
        userStatusPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        userListModel = new DefaultListModel<>();
        JList<String> userList = new JList<>(userListModel);
        JScrollPane userListScrollPane = new JScrollPane(userList);

        userListScrollPane.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        userListScrollPane.setPreferredSize(new Dimension(152, 800));
        userListScrollPane.setMaximumSize(new Dimension(152, 800));
        userList.setFont(getEmojiFont());

        userStatusPanel.add(userListScrollPane);
        userStatusPanel.add(Box.createVerticalStrut(5));

        /* ===== Rebuild User Info Button Panel with All Components ===== */
        userInfoButtonPanel.removeAll();
        userInfoButtonPanel.add(userInfo);
        userInfoButtonPanel.add(Box.createVerticalStrut(5));
        userInfoButtonPanel.add(userIdPanel);
        userInfoButtonPanel.add(Box.createVerticalStrut(5));
        userInfoButtonPanel.add(updateUserInfo);
        userInfoButtonPanel.add(Box.createVerticalStrut(40));
        userInfoButtonPanel.add(userStatus);
        userInfoButtonPanel.add(Box.createVerticalStrut(5));
        userInfoButtonPanel.add(userStatusPanel);
        userInfoButtonPanel.add(history);

        /* ===== Right User Info Panel (Additional Info) ===== */
        JPanel rightUserInfoButtonPanel = new JPanel();
        rightUserInfoButtonPanel.setLayout(new BoxLayout(rightUserInfoButtonPanel, BoxLayout.Y_AXIS));
        rightUserInfoButtonPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 10));

        rightUserInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        rightUserInfoButtonPanel.add(rightUserInfo);
        rightUserInfoButtonPanel.add(Box.createVerticalStrut(4));

        // Menu Panel
        JPanel menuPanel = new JPanel();
        menuPanel.setLayout(new BoxLayout(menuPanel, BoxLayout.X_AXIS));

        JTextArea fixedTextArea = getMenuText();
        fixedTextArea.setLineWrap(true);
        fixedTextArea.setWrapStyleWord(true);

        // Wrap text area inside a scroll pane
        JScrollPane scrollPane = new JScrollPane(fixedTextArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setPreferredSize(new Dimension(200, 150));

        menuPanel.setVisible(false);
        menuPanel.add(scrollPane);
        menuPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        rightUserInfoButtonPanel.add(menuPanel);
        rightUserInfoButtonPanel.add(Box.createVerticalStrut(4));


        /* ===== Typing Notification and South Panel ===== */
        JLabel typingLabel = new JLabel();
        typingLabel.setForeground(Color.GRAY);

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(inputPanel, BorderLayout.CENTER);
        southPanel.add(typingLabel, BorderLayout.SOUTH);

        /* ===== Assemble the Main Frame ===== */
        frame.add(chatScrollPane, BorderLayout.CENTER);
        frame.add(buttonPanel, BorderLayout.NORTH);
        frame.add(userInfoButtonPanel, BorderLayout.WEST);
        frame.add(rightUserInfoButtonPanel, BorderLayout.EAST);
        frame.add(southPanel, BorderLayout.SOUTH);
        setupFileClickListener();
        frame.setVisible(true);
        frame.revalidate();
        frame.repaint();

        /* ===== Event Listeners Setup ===== */
        sendButton.addActionListener(_ -> {
            if (selectedFile != null) {
                sendFile();
            } else {
                sendMessage(); // Normal text message
            }
        });

        history.addActionListener(_ -> requestChatHistory());
        attachButton.addActionListener(_ -> openFileChooser());

        inputField.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                if (inputField.getText().equals("Type a message")) {
                    inputField.setText("");
                    inputField.setForeground(Color.BLACK);
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (inputField.getText().trim().isEmpty()) {
                    inputField.setText("Type a message");
                    inputField.setForeground(Color.GRAY);
                }
            }
        });

        voiceMessageButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                startVoiceRecording();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                stopVoiceRecording();
            }
        });

        inputField.addKeyListener(new KeyAdapter() {
            private Timer typingTimer;
            private boolean isTyping = false;

            @Override
            public void keyPressed(KeyEvent e) {
                if (!isTyping) {
                    isTyping = true;
                    writer.println("TYPING:" + id + ":typing");
                    writer.flush();
                }

                if (typingTimer != null) {
                    typingTimer.restart();
                } else {
                    typingTimer = new Timer(60000, _ -> {
                        isTyping = false;
                        writer.println("TYPING_END:" + id);
                        writer.flush();
                        typingLabel.setText("");
                        southPanel.revalidate();
                        southPanel.repaint();
                    });
                    typingTimer.setRepeats(false);
                    typingTimer.start();
                }

                // If ENTER is pressed, stop typing indicator and check if message is for chatbot
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    String inputText = inputField.getText().trim();
                    if (inputText.startsWith("#")) {
                        writer.println(inputText); // Send chatbot message
                    } else {
                        sendMessage(); // Send normal message
                    }

                    if (typingTimer != null) {
                        typingTimer.stop();
                    }
                    isTyping = false;
                    writer.println("TYPING_END:" + id);
                    writer.flush();
                    typingLabel.setText("");
                    southPanel.revalidate();
                    southPanel.repaint();
                    inputField.setText("");
                }
            }
        });

        quitButton.addActionListener(_ -> quitChat());
        requestMemberButton.addActionListener(_ -> requestMemberList());
        rightUserInfo.addActionListener(_ -> {
            menuPanel.setVisible(!menuPanel.isVisible()); // Toggle visibility
        });
        updateUserInfo.addActionListener(_ -> {
            String newId = userIdField.getText().trim();

            if (newId.isEmpty() || newId.equals(id) || !newId.matches("[A-Za-z0-9_]+")) {
                chatArea.append("Invalid User ID. Please enter a different one.\n");
                return;
            }

            try {
                writer.println("CHANGE_ID: " + newId);

                String serverResponse = reader.readLine();

                if ("ID_EXISTS".equals(serverResponse)) {
                    chatArea.append("This ID is already in use. Please choose a different one.\n");
                } else if ("ID_ACCEPTED".equals(serverResponse)) {
                    String oldId = id;
                    id = newId;
                    chatArea.append("You updated your User ID from " + oldId + " to " + id + ".\n");

                    frame.setTitle("Chat Client - " + id);
                }
            } catch (IOException ex) {
                chatArea.append("Error communicating with the server. Please try again.\n");
            }

        });

        /* ===== Reaction Menu Setup ===== */
        reactionMenu = new JPopupMenu();
        reactionMenu.setLayout(new GridLayout(5, 4));

        // 20 random emojis
        String[] reactions = {"😃", "🤩", "❤️", "🔥",
                "👍", "🙌", "😂", "🤣",
                "😆", "🥳", "😮", "🤯",
                "😢", "😡", "😤", "👀",
                "🤔", "🙄", "💀", "🎉"};

        for (String reaction : reactions) {
            JMenuItem menuItem = new JMenuItem(reaction);
            menuItem.addActionListener(_ -> {
                if (lastSelectedMessage == null) {
                    chatArea.append("⚠️ Select a message first before reacting!\n");
                    return;
                }
                String userId = id;
                sendReaction(userId, lastSelectedMessage, reaction);
            });
            reactionMenu.add(menuItem);
        }

        /* ===== Chat Area Right-Click Listener for Reactions ===== */
        chatArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int offset = chatArea.viewToModel2D(e.getPoint());
                    try {
                        int rowStart = Utilities.getRowStart(chatArea, offset);
                        int rowEnd = Utilities.getRowEnd(chatArea, offset);
                        String selectedMessage = chatArea.getText(rowStart, rowEnd - rowStart).trim();

                        // Extract only the actual message by splitting at ":"
                        if (selectedMessage.contains(":")) {
                            lastSelectedMessage = selectedMessage.substring(selectedMessage.indexOf(":") +
                                    1).trim();
                        } else {
                            lastSelectedMessage = selectedMessage;
                        }

                        if (!lastSelectedMessage.isEmpty()) {
                            reactionMenu.show(e.getComponent(), e.getX(), e.getY());
                        } else {
                            chatArea.append("⚠️ No valid message selected.\n");
                        }
                    } catch (BadLocationException ex) {
                        logger.log(Level.SEVERE, "An error occurred", e);
                    }
                }
            }
        });

        frame.setVisible(true);
    }

    public static class WhatsAppEmojiPicker {
        private final JTextField inputField;
        private final JButton emojiButton;

        public WhatsAppEmojiPicker(JTextField inputField, JButton emojiButton) {
            this.inputField = inputField;
            this.emojiButton = emojiButton;
        }

        public JPopupMenu getJPopupMenu() {
            JPopupMenu emojiPopup = new JPopupMenu();
            JTabbedPane tabbedPane = new JTabbedPane();
            Font tabFont = new Font("Segoe UI Emoji", Font.PLAIN, 12);

            // Using proper category tabs
            tabbedPane.addTab(" ", createEmojiPanel(getFacesEmojis()));
            tabbedPane.setTabComponentAt(0, createTabLabel("😃", tabFont));

            tabbedPane.addTab(" ", createEmojiPanel(getAnimalsEmojis()));
            tabbedPane.setTabComponentAt(1, createTabLabel("🐵", tabFont));

            tabbedPane.addTab(" ", createEmojiPanel(getFoodEmojis()));
            tabbedPane.setTabComponentAt(2, createTabLabel("🍕", tabFont));

            tabbedPane.addTab(" ", createEmojiPanel(getSportsEmojis()));
            tabbedPane.setTabComponentAt(3, createTabLabel("⚽", tabFont));

            tabbedPane.addTab(" ", createEmojiPanel(getTravelEmojis()));
            tabbedPane.setTabComponentAt(4, createTabLabel("🚗", tabFont));

            tabbedPane.addTab(" ", createEmojiPanel(getObjectsEmojis()));
            tabbedPane.setTabComponentAt(5, createTabLabel("💡", tabFont));

            tabbedPane.addTab(" ", createEmojiPanel(getSymbolsEmojis()));
            tabbedPane.setTabComponentAt(6, createTabLabel("❤️", tabFont));

            JScrollPane emojiScrollPane = new JScrollPane(tabbedPane);
            emojiScrollPane.setPreferredSize(new Dimension(250, 220));

            emojiPopup.add(emojiScrollPane);

            // Show emoji picker when emoji button is clicked
            emojiButton.addActionListener(_ -> emojiPopup.show(emojiButton, 0, emojiButton.getHeight()));

            return emojiPopup;
        }

        private JLabel createTabLabel(String text, Font font) {
            JLabel label = new JLabel(text);
            label.setFont(font);
            label.setHorizontalAlignment(SwingConstants.CENTER);
            return label;
        }

        private JPanel createEmojiPanel(List<String> emojis) {
            JPanel emojiPanel = new JPanel(new GridLayout(5, 10, 5, 5));

            for (String emoji : emojis) {
                JButton emojiBtn = new JButton(emoji);
                emojiBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
                emojiBtn.setBorderPainted(false);
                emojiBtn.setFocusPainted(false);
                emojiBtn.setContentAreaFilled(false);

                // Insert emoji into the input field without closing popup
                emojiBtn.addActionListener(_ -> insertEmoji(emoji));

                emojiPanel.add(emojiBtn);
            }

            return emojiPanel;
        }

        // Method to insert emoji and handle placeholder behavior
        private void insertEmoji(String emoji) {
            if (inputField.getText().equals("Type a message")) {
                inputField.setText(emoji); // Replace placeholder
            } else {
                inputField.setText(inputField.getText() + emoji); // Append emoji
            }
            inputField.setForeground(Color.BLACK);
        }

        // Emoji categories
        private List<String> getFacesEmojis() {
            return Arrays.asList(
                    "😀", "😃", "😄", "😁", "😆", "🥹", "😅", "😂", "🤣", "🥲",
                    "️😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "👺",
                    "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐",
                    "🤓", "😎", "🥸", "🤩", "🥳", "🙂‍↕️", "😏", "😒", "🙂‍↔️", "😞",
                    "😔", "😟", "😕", "🙁", "😣", "😖", "😫", "😩", "🥺", "🤡",
                    "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶",
                    "😶‍🌫️", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🫣", "🤭",
                    "🫢", "🫡", "🤫", "🫠", "🤥", "😶", "🫥", "😐", "🫤", "😑",
                    "🫨", "😬", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴",
                    "🤤", "😪", "😮‍💨", "😵", "😵‍💫", "🤐", "🥴", "🤢", "🤮", "🤧",
                    "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👹", "💩", "👻"
            );
        }

        private List<String> getAnimalsEmojis() {
            return Arrays.asList(
                    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨",
                    "🐯", "🦁", "🐮", "🐷", "🐽", "🐸", "🐵", "🙈", "🙉", "🙊",
                    "🐒", "🐔", "🐧", "🐦", "🐤", "🐣", "🐥", "🪿", "🦆", "🐦‍⬛",
                    "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🫎", "🐝", "🪱",
                    "🐛", "🦋", "🐌", "🐞", "🐜", "🪰", "🪲", "🪳", "🦟", "🦗",
                    "🕷️", "🕸️", "🦂", "🐢", "🐍", "🦎", "🦖", "🦕", "🐙", "🦑",
                    "🪼", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🐋",
                    "🦈", "🦭", "🐊", "🐅", "🐆", "🦓", "🦍", "🦧", "🦣", "🐘",
                    "🦛", "🦏", "🐪", "🐫", "🦒", "🦘", "🦬", "🐃", "🐂", "🐄",
                    "🫏", "🐎", "🐖", "🐏", "🐑", "🦙", "🐐", "🦌", "🐕", "🐩",
                    "🦮", "🐕‍🦺", "🐈", "🐈‍⬛", "🪶", "🪽", "🐓", "🦃", "🦤", "🦚"
            );
        }

        private List<String> getFoodEmojis() {
            return Arrays.asList(
                    "🍏", "🍎", "🍐", "🍊", "🍋", "🍋‍🟩", "🍌", "🍉", "🍇", "🍓",
                    "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆",
                    "🥑", "🫛", "🥦", "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🫒",
                    "🧄", "🧅", "🥔", "🍠", "🫚", "🥐", "🥯", "🍞", "🥖", "🥨",
                    "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓", "🥩", "🍗", "🍖",
                    "🦴", "🌭", "🍔", "🍟", "🍕", "🫓", "🥪", "🥙", "🧆", "🌮",
                    "🌯", "🫔", "🥗", "🥘", "🫕", "🥫", "🫙", "🍝", "🍜", "🍲",
                    "🍛", "🍣", "🍱", "🥟", "🦪", "🍤", "🍙", "🍚", "🍘", "🍥",
                    "🥠", "🥮", "🍢", "🍡", "🍧", "🍨", "🍦", "🥧", "🧁", "🍰",
                    "🎂", "🍮", "🍭", "🍬", "🍫", "🍿", "🍩", "🍪", "🌰", "🥜",
                    "🫘", "🍯", "🥛", "🫗", "🍼", "🫖", "☕", "🍵", "🧃", "🥤"
            );
        }

        private List<String> getSportsEmojis() {
            return Arrays.asList(
                    "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
                    "🪀", "🏓", "🏸", "🏒", "🏑", "🥍", "🏏", "🪃", "🥅", "⛳",
                    "🪁", "🛝", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼",
                    "🛷", "🥌", "🎿", "🏂", "🪂", "🏋️‍♀️", "🏋️‍♂️", "🤼‍♀️", "🕴️", "🕺",
                    "🤼", "🤼‍♂️", "🤸‍♀️", "🤸", "🤸‍♂️", "⛹️‍♀️", "⛹️‍♂️", "🤺", "🤾‍♀️", "💃",
                    "🤾", "🤾‍♂️", "🏌️‍♀️", "🏌️", "🏌️‍♂️", "🏇", "🧘‍♀️", "🧘", "🧘‍♂️", "🏄‍♀️",
                    "🏄", "🏄‍♂️", "🏊‍♀️", "🏊", "🏊‍♂️", "🤽‍♀️", "🤽", "🤽‍♂️", "🚣‍♀️", "🚣",
                    "🚣‍♂️", "🧗‍♀️", "🧗", "🧗‍♂️", "🚵‍♀️", "🚵", "🚵‍♂️", "🚴‍♀️", "🚴", "🚴‍♂️",
                    "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🏵️", "🎗️", "🎫", "🎟️",
                    "🎪", "🤹‍♀️", "🤹‍♂️", "🎭", "🩰", "🎨", "🎬", "🎤", "🎧", "🎼",
                    "🎹", "🪇", "🥁", "🪘", "🎷", "🪗", "🎸", "🪕", "🎻", "🪈"
            );
        }

        private List<String> getTravelEmojis() {
            return Arrays.asList(
                    "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
                    "🛻", "🚚", "🚛", "🚜", "🦯", "🦽", "🦼", "🩼", "🛴", "🚲",
                    "🛵", "🏍️", "🛺", "🛞", "🚨", "🚔", "🚍", "🚘", "🚖", "🚡",
                    "🚠", "🚟", "🚋", "🚞", "🚝", "🚄", "🚅", "🚈", "🚂", "🚆",
                    "🚇", "🚊", "🚉", "🛫", "🛬", "🛩️", "💺", "🛰️", "🚀", "🏥",
                    "🛸", "🚁", "🛶", "⛵", "🚤", "🛥️", "🛳️", "🚢", "🛟", "🕍",
                    "⚓", "🪝", "⛽", "🚧", "🚦", "🚥", "🚏", "🗺️", "🗿", "🗽",
                    "🗼", "🏰", "🏯", "🏟️", "🎡", "🎢", "🎠", "⛲", "🏖️", "🕌",
                    "🏝️", "🏜️", "🌋", "🏔️", "🗻", "🏕️", "⛺", "🛖", "🏠", "⛪",
                    "🏡", "🏘️", "🏚️", "🏗️", "🏭", "🏢", "🏬", "🏣", "🏤", "🏛️",
                    "🏦", "🏨", "🏪", "🏫", "🏩", "💒", "🏳️", "🏴", "🏴‍☠️", "🏁"
            );
        }

        private List<String> getObjectsEmojis() {
            return Arrays.asList(
                    "⌚", "📱", "📲", "💻", "🖥️", "🖨️", "🖱️", "🖲️", "🕹️", "💵",
                    "🗜️", "💽", "💾", "💿", "📀", "📼", "📷", "📸", "📹", "🎥",
                    "📽️", "🎞️", "📞", "📟", "📠", "📺", "📻", "🎙️", "🎚️", "🔪",
                    "🎛️", "🧭", "⏰", "🕰️", "⌛", "⏳", "📡", "🔋", "💎", "🪜",
                    "🪫", "🔌", "💡", "🔦", "🕯️", "🪔", "🧯", "🛢️", "💸", "🪓",
                    "💴", "💶", "💷", "🪙", "💰", "💳", "🪪", "🌡️", "🧹", "🪠",
                    "🧰", "🪛", "🔧", "🔨", "🛠️", "🪚", "🔩", "🩹", "🩺", "🧪",
                    "🪤", "🧱", "⛓️‍💥", "🧲", "🔫", "💣", "🧨", "🧬", "🦠", "🧫",
                    "🗡️", "🛡️", "🚬", "🪦", "🏺", "🔮", "📿", "💊", "💉", "🩸",
                    "🧿", "🪬", "💈", "🔭", "🔬", "🕳️", "🩻", "🔓", "🔒", "🖌️",
                    "🔈", "🔇", "🔉", "🔊", "🔔", "🔕", "📣", "📢", "💬", "💭"
            );
        }

        private List<String> getSymbolsEmojis() {
            return Arrays.asList(
                    "🩷", "🧡", "💛", "💚", "🩵", "💙", "💜", "🖤", "🩶", "🚳",
                    "🤍", "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗", "🚱",
                    "💖", "💘", "💝", "💟", "🕉️", "🪯", "🔆", "🚸", "🔱", "🔅",
                    "🔯", "🕎", "🛐", "⛎", "♈", "♉", "♊", "🔰", "🈹", "💠",
                    "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓", "🆔",
                    "🉑", "📴", "📳", "🈶", "🈚", "🈸", "🈺", "❌", "⭕", "🛑",
                    "🈷️", "🆚", "💮", "🉐", "㊗", "🈴", "🈵", "🌐", "🚯", "❔",
                    "🈲", "🅰️", "🅱️", "🆎", "🆑", "🅾️", "🆘", "❓", "❕", "🚷",
                    "⛔", "📛", "🚫", "💯", "💢", "🔞", "📵", "🚭", "❗", "🕐",
                    "🕑", "🕒", "🕓", "🕕", "🕖", "🕗", "🕘", "🕙", "🕚", "🕛",
                    "🕝", "🕞", "🕠", "🕡", "🕢", "🕣", "🕤", "🕥", "🕦", "🕧"
            );
        }
    }

    // ===== Open File Chooser and Handle Selection =====
    private void openFileChooser() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select a file to send");
        int returnValue = fileChooser.showOpenDialog(frame);

        if (returnValue == JFileChooser.APPROVE_OPTION) {
            selectedFile = fileChooser.getSelectedFile();
            inputField.setText(selectedFile.getName());
            inputField.setForeground(Color.BLACK);
            sentFiles.put(selectedFile.getName(), selectedFile);
        }
    }

    // ===== Send File When Clicking Send Button =====
    private void sendFile() {
        if (selectedFile == null) return; // No file selected

        String fileName = selectedFile.getName();
        String fileExtension = getFileExtension(fileName);
        String fileIcon = getFileIcon(fileExtension);

        // Store the file in sentFiles (only for the sender)
        sentFiles.put(fileName, selectedFile);

        // Send only file name to the server, NOT the path
        writer.println(id + " sent a file: " + fileIcon + " " + fileName);

        // Reset input field after sending
        inputField.setText("Type a message");
        inputField.setForeground(Color.GRAY);

        selectedFile = null; // Clear selected file
    }

    // ===== Set Up Double-Click to Open Files or Play Voice Messages =====
    private void setupFileClickListener() {
        chatArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { // Double-click
                    int offset = chatArea.viewToModel2D(e.getPoint());
                    try {
                        int rowStart = Utilities.getRowStart(chatArea, offset);
                        int rowEnd = Utilities.getRowEnd(chatArea, offset);
                        String selectedText = chatArea.getText(rowStart, rowEnd - rowStart).trim();

                        if (selectedText.contains(" sent a file: ")) {
                            // Extract file name and open it
                            String clickedFileName = selectedText.substring(selectedText.lastIndexOf(" ")
                                    + 1);
                            File fileToOpen = sentFiles.get(clickedFileName);

                            if (fileToOpen != null && fileToOpen.exists()) {
                                openFile(fileToOpen);
                            } else {
                                JOptionPane.showMessageDialog(null, "File not found: " +
                                                clickedFileName,
                                        "Error", JOptionPane.ERROR_MESSAGE);
                            }
                        } else if (selectedText.contains(" sent a voice message: ")) {
                            // Extract voice message file name
                            String voiceFileName = selectedText.substring(selectedText.lastIndexOf(" ")
                                    + 1);
                            File voiceFile = sentFiles.get(voiceFileName);

                            if (voiceFile != null && voiceFile.exists()) {
                                playVoiceMessage(voiceFile); // Play the voice message
                            } else {
                                JOptionPane.showMessageDialog(null, "Voice message not found!",
                                        "Error", JOptionPane.ERROR_MESSAGE);
                            }
                        }

                    } catch (BadLocationException ex) {
                        logger.log(Level.SEVERE, "An error occurred while processing file open request.");
                    }
                }
            }
        });
    }

    // ===== Open the File in the Default Application =====
    private void openFile(File file) {
        if (file == null || !file.exists()) {
            JOptionPane.showMessageDialog(null, "File not found!",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Cannot open file: " + file.getName(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            logger.log(Level.SEVERE, "An error occurred while opening the file");
        }
    }

    // ===== Helper Method to Get File Extension =====
    private String getFileExtension(String fileName) {
        int lastIndex = fileName.lastIndexOf(".");
        return (lastIndex == -1) ? "" : fileName.substring(lastIndex + 1).toLowerCase();
    }

    // ===== Helper Method to Choose File Icon Based on Extension =====
    private String getFileIcon(String extension) {
        return switch (extension) {
            case "txt" -> "📄";  // Text File
            case "pdf" -> "📕";  // PDF
            case "doc", "docx" -> "📘";  // Word Document
            case "xls", "xlsx" -> "📗";  // Excel File
            case "ppt", "pptx" -> "📙";  // PowerPoint
            case "jpg", "jpeg", "png", "gif" -> "🖼";  // Images
            case "mp3", "wav" -> "🎵";  // Audio
            case "mp4", "avi" -> "🎬";  // Video
            case "zip", "rar" -> "📦";  // Compressed File
            default -> "📁";  // Generic File Icon
        };
    }

    private void startVoiceRecording() {
        try {
            voiceButtonPressTime = System.currentTimeMillis();

            AudioFormat format = new AudioFormat(16000, 16, 2, true,
                    true);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                JOptionPane.showMessageDialog(frame, "Microphone not supported!", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();

            inputField.setEditable(false);
            inputField.setText("🎤 Recording...");
            inputField.setForeground(Color.RED);
            inputField.setBackground(Color.WHITE);

            voiceMessageFile = new File("voice_message.wav");
            isRecording = true;
            new Thread(() -> writeAudioToFile(voiceMessageFile)).start();

        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error starting voice recording!", "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopVoiceRecording() {
        if (!isRecording) return;

        isRecording = false;
        microphone.stop();
        microphone.close();

        inputField.setEditable(true);
        inputField.setText("Type a message");
        inputField.setForeground(Color.GRAY);

        long elapsed = System.currentTimeMillis() - voiceButtonPressTime;

        if (elapsed >= 1000) {
            sendVoiceMessage(voiceMessageFile);
        } else {
            if (!voiceMessageFile.delete()) {
                System.err.println("⚠️ Failed to delete short voice message.");
            }
        }

    }

    private void writeAudioToFile(File file) {
        try (AudioInputStream audioStream = new AudioInputStream(microphone)) {
            while (isRecording) {
                AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, file);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Error saving voice message!", "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sendVoiceMessage(File file) {
        if (file == null || !file.exists()) return;

        String fileName = file.getName();
        writer.println(id + " sent a voice message: " + fileName);

        sentFiles.put(fileName, file);
    }

    // ===== Open and Play the Voice Message in Windows Media Player =====
    private void playVoiceMessage(File file) {
        if (file == null || !file.exists()) {
            JOptionPane.showMessageDialog(frame, "Voice message not found!", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            // Open file with the system's default application (Windows Media Player, VLC, etc.)
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Cannot play voice message!", "Error",
                    JOptionPane.ERROR_MESSAGE);
            logger.log(Level.SEVERE, "An error occurred while opening the voice message file.");
        }
    }

    private void requestChatHistory() {
        writer.println("REQUEST_CHAT_HISTORY");
    }

    private static Font getEmojiFont() {
        String os = System.getProperty("os.name").toLowerCase();
        Font emojiFont;

        if (os.contains("win")) {
            emojiFont = new Font("Segoe UI Emoji", Font.PLAIN, 14);
        } else if (os.contains("mac")) {
            emojiFont = new Font("Apple Color Emoji", Font.PLAIN, 14);
        } else {
            emojiFont = new Font("Noto Color Emoji", Font.PLAIN, 14);
        }

        return emojiFont;
    }

    // Method to create an icon button (Emoji, Attachment, Voice Messages)
    private static JButton createIconButton(String iconText) {
        JButton button = new JButton(iconText);
        button.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private TopLeftAlignedTextField getUserIdField() {
        TopLeftAlignedTextField userIdField = new TopLeftAlignedTextField(id);
        userIdField.setEditable(true);
        userIdField.setFont(new Font("Arial", Font.PLAIN, 14));
        userIdField.setMaximumSize(new Dimension(148, 25));
        userIdField.setPreferredSize(new Dimension(148, 25));
        userIdField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));

        return userIdField;
    }

    private static JTextArea getMenuText() {
        JTextArea fixedTextArea = new JTextArea(
                """
               ================
               🎉 Welcome to the Ultimate Chat Experience!
               ================
               \s
               🚀 Group-Based Communication
               \s
               Chat in real-time with multiple users in a distributed network. The coordinator manages the\s
               group, and all members are notified of them. If they leave, another user takes over automatically.
               \s
               ----------------------------
               \s
               💬 Messaging Features\s
               \s
               ✨ @Username Message → Send private messages. \s
               \s
               ✨ Broadcast → Chat with everyone. \s
               \s
               ✨ Edit/Delete → Double-click your message to modify or remove it. \s
               \s
               ✨ Emoji Reactions → Right-click on a message to react. \s
               \s
               ✨ AI Commands → Use #keyword (e.g., `#weather`) to get AI-generated responses. \s
               \s
               ----------------------------
               \s
               🛠 User Controls \s
               \s
               🟡 Request Members → View active users, including their name, IP, and coordinator status. \s
               \s
               🟡 Update ID → Change your username (valid & unique names only). \s
               \s
               🟡 Status Panel → Check who's online or offline. \s
               \s
               🟡 History Log → Chat is saved in `chat_log.txt` and resets when the server shuts down. \s
               \s
               ----------------------------
               \s
               📎 Multimedia Sharing \s
               \s
               🎭 Send emojis to express yourself. \s
               \s
               📂 Attach files & documents for sharing. \s
               \s
               🎙️ Record & send voice messages effortlessly. \s
               \s
               ----------------------------
               \s
               🔴 Exit Anytime \s
               \s
               Hit Quit when you’re ready to leave. \s
               \s
               ----------------------------
               \s
               💡 Enjoy chatting, stay connected, and have fun! \s
               """
        );
        fixedTextArea.setFont(getEmojiFont());
        fixedTextArea.setEditable(false);
        fixedTextArea.setBackground(Color.WHITE);
        fixedTextArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        fixedTextArea.setMaximumSize(new Dimension(300, 900));
        return fixedTextArea;
    }

    private void sendReaction(String userId, String messageText, String reaction) {
        if (messageText == null || messageText.trim().isEmpty()) {
            chatArea.append("⚠️ No message selected for reaction.\n");
            return;
        }

        // Format reaction message with the actual message text
        String broadcastMessage = userId + " reacted to: \"" + messageText + "\" with " + reaction;
        writer.println(broadcastMessage + "\n");
    }

    private void handleReactionMessage(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length < 3) return; // Invalid reaction message format
        String messageId = parts[1];
        String reaction = parts[2];
        chatArea.append("Message " + messageId + " received reaction: " + reaction + "\n");
    }

    private void setupMessageContextMenu() {
        chatArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    int offset = chatArea.viewToModel2D(e.getPoint());
                    try {
                        int rowStart = Utilities.getRowStart(chatArea, offset);
                        int rowEnd = Utilities.getRowEnd(chatArea, offset);
                        String selectedText = chatArea.getText(rowStart, rowEnd - rowStart);
                        if (isOwnMessage(selectedText)) {  // Only proceed if it's the user's message
                            JPopupMenu contextMenu = getContextMenu(selectedText);
                            contextMenu.show(chatArea, e.getX(), e.getY());
                        }
                    } catch (Exception ex) {
                        logger.log(Level.SEVERE, "An error occurred while setting up the message context menu",
                                ex);
                    }
                }
            }

            private JPopupMenu getContextMenu(String selectedText) {
                JPopupMenu contextMenu = new JPopupMenu();
                JMenuItem editItem = new JMenuItem("Edit");
                JMenuItem deleteItem = new JMenuItem("Delete");

                // Action to edit the selected message
                editItem.addActionListener(_ -> editMessage(selectedText));

                // Action to delete the selected message
                deleteItem.addActionListener(_ -> deleteMessage(selectedText));

                contextMenu.add(editItem);
                contextMenu.add(deleteItem);

                return contextMenu;
            }

            // Edit the message in the chat area
            private void editMessage(String message) {
                String messageContent = getMessageContent(message);
                String newContent = JOptionPane.showInputDialog("Edit Message", messageContent);

                if (newContent != null && !newContent.trim().isEmpty()) {
                    // Construct the updated message
                    String updatedMessage = message.split(":")[0] + ": " + newContent;

                    // Send edit request to the server
                    writer.println("EDIT_MESSAGE:" + message + ":" + updatedMessage);

                    replaceMessageInChatArea(message, updatedMessage);
                }
            }


            // Replace the old message with the new message in the chat area
            private void replaceMessageInChatArea(String oldMessage, String newMessage) {
                String chatText = chatArea.getText();
                // Replace the old message with the new message in the entire chat text
                chatText = chatText.replaceFirst(Pattern.quote(oldMessage), Matcher.quoteReplacement(newMessage));
                chatArea.setText(chatText);
            }

            // Delete the message by replacing it
            private void deleteMessage(String message) {
                String chatText = chatArea.getText();
                chatText = chatText.replaceFirst(Pattern.quote(message) + "\n?", "");
                chatArea.setText(chatText);
            }

            // Check if the message belongs to the user
            private boolean isOwnMessage(String message) {
                return message.startsWith(id + ":");
            }

            // Extract the message content after the colon
            private String getMessageContent(String message) {
                int colonIndex = message.indexOf(":");
                return colonIndex != -1 ? message.substring(colonIndex + 1).trim() : message;
            }
        });
    }

    public void handleTypingEvent(String message) {
        if (message.startsWith("TYPING:")) {
            String[] parts = message.split(":");
            String user = parts[1];  // extract user name

            // Display typing notification only if the message is not from the current user
            if (!user.equals(id)) {
                SwingUtilities.invokeLater(() -> chatArea.append(user + " is typing...\n"));
            }
        } else if (message.startsWith("TYPING_END:")) {
            String user = message.substring(11);  // extract user ID

            // Clear typing notification only if the message is not from the current user
            if (!user.equals(id)) {
                SwingUtilities.invokeLater(() -> chatArea.append(user + " has stopped typing.\n"));
            }
        }
    }

    // Handles sending messages to the server
    private void sendMessage() {
        String message = inputField.getText().trim();

        if (message.isEmpty() || message.equals("Type a message")) {
            return; // Do nothing if empty or still contains the placeholder
        }

        if (message.equals("REQUEST_MEMBER_LIST") || message.equals("ACTIVE_CHECK") || message.startsWith("@")) {
            writer.println(message); // Send command directly
        } else {
            writer.println(id + ": " + message); // Send normal chat messages
        }

        SwingUtilities.invokeLater(() -> {
            inputField.setText(""); // Clear the input field after sending the message
            if (!inputField.isFocusOwner()) {
                inputField.setText("Type a message"); // Reset if not focused
                inputField.setForeground(Color.GRAY);
            }
        });
    }

    private class MessageReceiver extends Thread {
        public void run() {
            try {
                String message;
                while ((message = reader.readLine()) != null) {
                    processMessage(message);
                }
            } catch (IOException e) {
                if (!socket.isClosed()) { // Avoid printing stack trace if the socket was closed intentionally
                    chatArea.append("Server has closed the connection.\n");
                }
            } finally {
                disableInput(); // Disable input when disconnected
            }
        }

        private void processMessage(String message) {
            if (message.startsWith("CHANGE_ID:") || message.startsWith("TYPING_END:") ||
                    message.startsWith("REQUEST_CHAT_HISTORY")) {
                return; // Ignore ID change and TYPING_END notifications
            }

            // Use handleTypingEvent to manage typing events
            if (message.startsWith("TYPING:")) {
                handleTypingEvent(message);
            } else if (message.startsWith("EDIT_MESSAGE:")) {
                handleEditMessage(message);
            } else if (message.startsWith("DELETE_MESSAGE:")) {
                handleDeleteMessage(message);
            } else if (message.startsWith("(Private)")) {
                handlePrivateMessage(message);
            } else if (message.startsWith("REACTION:")) {
                handleReactionMessage(message);
            } else if (message.startsWith("STATUS:")) {
                handleStatusMessage(message);
            } else if (message.startsWith("MESSAGE_ID:")) {
                handleNewMessage(message);
            } else if (message.startsWith("CHAT_HISTORY:")) {
                handleChatHistory(message);
            } else {
                chatArea.append(message + "\n");
            }
        }

        private void handleChatHistory(String message) {
            String delimiter = "\n----------------------------------------------\n";
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime now = LocalDateTime.now();
            String dateTime = dtf.format(now);

            String[] historyMessages = message.substring(13).split("\n");

            chatArea.append(delimiter);
            chatArea.append(dateTime);
            chatArea.append(delimiter);
            loadChatHistory();
            for (String historyMessage : historyMessages) {
                if (!historyMessage.startsWith("TYPING:") && !historyMessage.startsWith("TYPING_END:") &&
                        !historyMessage.startsWith("REQUEST_CHAT_HISTORY") && !historyMessage.startsWith("STATUS:")) {
                    chatArea.append(historyMessage);
                }
            }
        }

        private void handleEditMessage(String message) {
            String[] parts = message.split(":", 3);
            if (parts.length < 3) {
                System.out.println("Invalid message format: " + message);
                return;
            }

            String oldMessage = parts[1].trim();
            String newMessage = parts[2].trim();

            // If newMessage starts with extra prefix, remove it
            if (newMessage.contains(":")) {
                newMessage = newMessage.substring(newMessage.indexOf(":") + 1).trim();
            }

            updateMessageInChat(oldMessage, newMessage);
        }

        private void updateMessageInChat(String oldMessage, String newMessage) {
            String chatText = chatArea.getText();
            String[] lines = chatText.split("\n");
            StringBuilder updatedChat = new StringBuilder();

            for (String line : lines) {
                if (line.trim().equals(oldMessage)) { // Replace only exact matches
                    updatedChat.append(newMessage).append("\n");
                } else {
                    updatedChat.append(line).append("\n"); // Keep other messages unchanged
                }
            }

            chatArea.setText(updatedChat.toString());
        }

        private void handleDeleteMessage(String message) {
            String messageToDelete = message.substring(14).trim();
            String chatText = chatArea.getText();
            chatText = chatText.replaceFirst(Pattern.quote(messageToDelete) + "\n?", "");
            chatArea.setText(chatText);
        }

        private void handleNewMessage(String message) {
            String[] parts = message.split(":", 3);
            int messageId = Integer.parseInt(parts[1]);
            String content = parts[2];
            messages.put(messageId, content);
            chatArea.append(content + "\n");
        }

        private void loadChatHistory() {
            for (Map.Entry<Integer, String> entry : messages.entrySet()) {
                chatArea.append(entry.getValue() + "\n");  // Display stored messages
            }
        }


        private void handlePrivateMessage(String message) {
            String[] parts = message.split(": ", 2);
            if (parts.length < 2) return;

            String sender = parts[0].replace("(Private)", "").trim(); // Extract sender's name
            String privateMessage = parts[1];

            // If no chat window exists for this sender, create one
            if (!privateChats.containsKey(sender)) {
                privateChats.put(sender, new ChatWindow(sender, writer));
            }

            // Append the message to the correct private chat
            privateChats.get(sender).appendMessage(sender + ": " + privateMessage);
        }

        private void handleStatusMessage(String message) {
            String[] parts = message.split(":");
            if (parts.length == 3) {
                String userId = parts[1];
                String status = parts[2];
                SwingUtilities.invokeLater(() -> updateUserStatus(userId, status));
            }
        }
    }

    private void updateUserStatus(String userId, String status) {
        String onlineUser = userId + " ✅";
        String offlineUser = userId + " ❌";
        if (status.equals("online")) {
            if (!userListModel.contains(onlineUser)) {
                userListModel.addElement(onlineUser);
            }
            userListModel.removeElement(offlineUser);
        } else if (status.equals("offline")) {
            userListModel.removeElement(onlineUser);
            if (!userListModel.contains(offlineUser)) {
                userListModel.addElement(offlineUser);
            }
        }
    }

    // Handles quitting the chat
    private void quitChat() {
        JDialog dialog = new JDialog(frame, "Confirm Exit", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(300, 150);
        dialog.setLocationRelativeTo(frame);

        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        JLabel messageLabel = new JLabel("Do you really want to quit?");
        messageLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        messageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        messagePanel.add(messageLabel, BorderLayout.CENTER);

        JLabel countdownLabel = new JLabel("59");
        countdownLabel.setFont(new Font("Segoe UI Emoji", Font.BOLD, 18));
        countdownLabel.setForeground(Color.BLACK);
        countdownLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel buttonPanel = new JPanel();
        JButton yesButton = new JButton("Yes");
        JButton noButton = new JButton("No");

        yesButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        noButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        buttonPanel.add(yesButton);
        buttonPanel.add(noButton);

        dialog.add(messagePanel, BorderLayout.NORTH);
        dialog.add(countdownLabel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        Timer timer = getTimer(countdownLabel, dialog);

        // Handle "Yes" button click
        yesButton.addActionListener(_ -> {
            timer.stop(); // Stop countdown if user clicks "Yes"
            dialog.dispose();
            closeChat();
        });

        // Handle "No" button click
        noButton.addActionListener(_ -> {
            timer.stop(); // Stop countdown if user clicks "No"
            dialog.dispose(); // Close the dialog, but keep the app open
        });

        dialog.setVisible(true);
    }

    private Timer getTimer(JLabel countdownLabel, JDialog dialog) {
        final int[] secondsRemaining = {59}; // Countdown from 59 seconds

        Timer timer = new Timer(1000, e -> {
            secondsRemaining[0]--;
            countdownLabel.setText(String.valueOf(secondsRemaining[0]));
            if (secondsRemaining[0] == 0) {
                ((Timer) e.getSource()).stop(); // Stop timer when it reaches 0
                dialog.dispose();
                closeChat();
            }
        });

        timer.start(); // Start countdown
        return timer;
    }

    /**
     * Closes the chat by shutting down the socket and closing the UI.
     */
    private void closeChat() {
        try {
            if (socket != null) {
                socket.close(); // Close socket connection
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "An error occurred while quitting the conversation", e);
        } finally {
            frame.dispose(); // Close GUI
        }
    }

    // Requests the list of active chat members from the server
    private void requestMemberList() {
        writer.println("REQUEST_MEMBER_LIST");
    }
    
    // Disables the input field and send button when disconnected
    private void disableInput() {
        SwingUtilities.invokeLater(() -> {
            if (inputField != null) {
                inputField.setEnabled(false);
            } else {
                System.err.println("Warning: inputField is null! Cannot disable input.");
            }
        });
    }

    // Validates if the input is a proper ID
    private static boolean isValidID(String id) {
        return id.matches("[A-Za-z0-9_]+");
    }

    // Validates if the input is a proper IPv4 address or a hostname
    private static boolean isValidIP(String ip) {
        // IPv4 strict: Ensures numbers are between 0-255 and properly formatted
        String ipv4Pattern = "^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
                + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$";

        // Allows domain names like "example.com" or "localhost"
        String hostnamePattern = "^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$|^localhost$";

        return Pattern.matches(ipv4Pattern, ip) || Pattern.matches(hostnamePattern, ip);
    }

    // Main method to start the chat client
    public static void main(String[] args) {
        // Create input fields
        JTextField idField = new JTextField(15);
        JTextField ipField = new JTextField("127.0.0.1", 15);
        JTextField portField = new JTextField("5000", 15);

        // Style label + field pairs in a Grid
        JPanel gridPanel = new JPanel(new GridBagLayout());
        gridPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Labels and fields
        addRow(gridPanel, gbc, 0, "🆔 Enter your ID:", idField, true);
        addRow(gridPanel, gbc, 1, "🌐 Server IP:", ipField, false);
        addRow(gridPanel, gbc, 2, "📦 Port (1024 - 65535):", portField, false);

        // Loop until valid input or cancel
        while (true) {
            int result = JOptionPane.showConfirmDialog(null, gridPanel, "🚀 Client Setup",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (result != JOptionPane.OK_OPTION) {
                JOptionPane.showMessageDialog(null, "Client setup canceled.", "Exit",
                        JOptionPane.INFORMATION_MESSAGE);
                System.exit(0);
            }

            String id = idField.getText().trim();
            String ip = ipField.getText().trim();
            String portText = portField.getText().trim();

            if (validateInput(id, ip, portText)) {
                int port = Integer.parseInt(portText);
                new ChatClient(id, ip, port); // Your existing method
                break;
            }
        }
    }

    private static void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field,
                               boolean setFocus) {
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        panel.add(field, gbc);

        if (setFocus) {
            SwingUtilities.invokeLater(() -> {
                field.requestFocusInWindow();
                field.selectAll();
            });
        }
    }

    private static boolean validateInput(String id, String serverIP, String portInput) {
        if (!isValidID(id)) {
            JOptionPane.showMessageDialog(null, "Invalid ID. Only letters, numbers, and " +
                    "underscores are allowed.", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        if (!isValidIP(serverIP)) {
            JOptionPane.showMessageDialog(null, "Invalid Server IP. Enter a valid IPv4 or " +
                    "hostname.", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        try {
            int port = Integer.parseInt(portInput);
            if (port < 1024 || port > 65535) {
                JOptionPane.showMessageDialog(null, "Invalid port. Enter a number between " +
                        "1024 and 65535.", "Error", JOptionPane.ERROR_MESSAGE);
                return false;
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "Invalid input. Please enter a valid port " +
                    "number.", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    public static class ChatWindow extends JFrame {
        private final JTextArea chatArea;
        private final JTextField messageField;
        private final PrintWriter writer;
        private final String recipient;

        public ChatWindow(String recipient, PrintWriter writer) {
            this.recipient = recipient;
            this.writer = writer;

            setTitle("Private Chat - " + recipient);
            setSize(400, 400);
            setLayout(new BorderLayout());

            chatArea = new JTextArea();
            chatArea.setEditable(false);
            add(new JScrollPane(chatArea), BorderLayout.CENTER);

            messageField = new JTextField();
            messageField.addActionListener(_ -> sendMessage());
            add(messageField, BorderLayout.SOUTH);

            setVisible(true);
        }

        public void appendMessage(String message) {
            chatArea.append(message + "\n");
        }

        private void sendMessage() {
            String message = messageField.getText().trim();
            if (!message.isEmpty()) {
                writer.println("@" + recipient + " " + message);
                appendMessage("Me: " + message);
                messageField.setText("");
            }
        }
    }
}
