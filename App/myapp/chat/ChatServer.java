/**
 * Giulio Dajani
 * 02/04/2025
 */

package myapp.chat;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import javax.swing.JOptionPane;
import java.util.logging.Logger;


public class ChatServer {
    private static final Set<ClientHandler> clients = Collections.newSetFromMap(new ConcurrentHashMap<>()); // Set of
    // connected clients
    private static ClientHandler coordinator; // The coordinator client (first connected)
    private static ServerSocket serverSocket; // Server socket to listen for connections
    private static final Logger logger = Logger.getLogger(ChatServer.class.getName()); // Catch exception errors
    public static boolean running = true; // Controls whether the server is running
    // Scheduler for periodic tasks
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Set<String> activeIDs = ConcurrentHashMap.newKeySet(); // Track active IDs to ensure uniqueness
    private static ScheduledFuture<?> activeCheckTask; // Store scheduled task
    static final Map<String, List<String>> messageReactions = new ConcurrentHashMap<>();
    static Map<Pattern, List<String>> triggers = new HashMap<>();
    private static final Map<Integer, String> messages = Collections.synchronizedMap(new HashMap<>());
    private static final String LOG_FILE = "chat_log.txt";

    public static void main(String[] args) {
        try {
        	// Ask user for the port number to run the server on
            int PORT = 5000; // Default port on which the server will listen
            while (true) {
                String portInput = (String) JOptionPane.showInputDialog(
                        null,
                        "Enter Port (1024 - 65535):",
                        "🔌 Server Port Setup",
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        null,
                        String.valueOf(PORT)
                );

                if (portInput == null) {
                    // User cancelled
                    JOptionPane.showMessageDialog(
                            null,
                            "Server setup canceled. 👋",
                            "Exit",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                    System.exit(0);
                }

                portInput = portInput.trim(); // Remove extra spaces

                if (portInput.isEmpty()) {
                    // Use default
                    JOptionPane.showMessageDialog(
                            null,
                            "No port entered. Using default port 5000. 🚀",
                            "Info",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                    break;
                }

                try {
                    int parsedPort = Integer.parseInt(portInput);
                    if (parsedPort >= 1024 && parsedPort <= 65535) {
                        PORT = parsedPort;

                        JOptionPane.showMessageDialog(
                                null,
                                "🌟 Server will start on port " + PORT + "!",
                                "✅ Starting Server",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                        break;
                    } else {
                        JOptionPane.showMessageDialog(
                                null,
                                "❌ Invalid port! Enter a number between 1024 and 65535.",
                                "Port Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(
                            null,
                            "❌ Invalid input. Please enter a valid number.",
                            "Format Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }

        	// Keep prompting until a free port is provided
        	while (true) {
        	    try {
        	        serverSocket = new ServerSocket(PORT);
        	        System.out.println("Server started on port " + PORT);
        	        break; // Exit loop if the port is successfully bound
        	    } catch (BindException e) {
        	        // Show error and prompt for a new port
        	        JOptionPane.showMessageDialog(null, "Port " + PORT + " is already in use." +
                            " Enter a different port.", "Port Error", JOptionPane.ERROR_MESSAGE);

        	        while (true) {
        	            String newPortInput = JOptionPane.showInputDialog("Enter a new Port (1024 - 65535):");

        	            if (newPortInput == null) {
        	                JOptionPane.showMessageDialog(null, "Server setup canceled.",
                                    "Exit", JOptionPane.INFORMATION_MESSAGE);
        	                System.exit(0);
        	            }

        	            newPortInput = newPortInput.trim(); // Remove spaces

        	            if (!newPortInput.isEmpty()) {
        	                try {
        	                    int newPort = Integer.parseInt(newPortInput);
        	                    if (newPort >= 1024 && newPort <= 65535) {
        	                        PORT = newPort;
        	                        break; // Exit inner loop and retry binding with new port
        	                    } else {
        	                        JOptionPane.showMessageDialog(null, "Invalid port. " +
                                            "Enter a number between 1024 and 65535.", "Error",
                                            JOptionPane.ERROR_MESSAGE);
        	                    }
        	                } catch (NumberFormatException ex) {
        	                    JOptionPane.showMessageDialog(null, "Invalid input. Please " +
                                        "enter a valid port number.", "Error", JOptionPane.ERROR_MESSAGE);
        	                }
        	            } else {
        	                JOptionPane.showMessageDialog(null, "Port cannot be empty.",
                                    "Error", JOptionPane.ERROR_MESSAGE);
        	            }
        	        }
        	    } catch (IOException e) {
                    logger.log(Level.SEVERE, "Error initializing server socket", e);
        	        System.exit(1); // If another error occurs, exit the program
        	    }
        	}

        	// Start active check timer
        	restartActiveCheckTimer();

            // Start a separate thread to listen for shutdown commands
            new Thread(ChatServer::listenForCommands).start();

            // Accept new client connections in a loop
            while (running) {
                Socket socket = serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(socket);
                clients.add(clientHandler);
                new Thread(clientHandler).start(); // Start a new thread for each client
            }
        } catch (IOException e) {
            if (running) {
                logger.log(Level.SEVERE, "An error occurred while running the server", e);
            }
        }
    }

    // Listens for server shutdown commands from the console
    private static void listenForCommands() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (running) {
                String command = reader.readLine();
                if ("exit".equalsIgnoreCase(command)) {
                    System.out.println("Shutting down server...");
                    notifyClientsShutdown(); // Notify clients before shutting down
                    stopServer();
                    break;
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "The request of shutting down the server has produced an unknown error", e);
        }
    }

    // Notify all clients that the server is shutting down
    static void notifyClientsShutdown() {
        for (ClientHandler client : clients) {
            client.sendMessage("Server is shutting down. You will be disconnected.");
        }
        try {
            Thread.sleep(2000); // Give clients time to receive the message
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Check which members are still active
    private static void checkActiveMembers() {
        if (coordinator != null) {
            coordinator.sendMessage("Checking active members...");
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    if (client != coordinator) {
                        client.sendMessage("Still Active?");
                    }
                }
            }
        }
    }

    // Stop the server if there are no more connected clients
    public static synchronized void stopServerIfEmpty() {
        if (clients.isEmpty()) {
            System.out.println("All clients have disconnected. Stopping server...");
            stopServer();
        }
    }

    // Stops the server and releases resources
    public static void stopServer() {
        try {
            running = false;
            scheduler.shutdown(); // Stop periodic tasks

            for (ClientHandler client : clients) {
                client.closeConnection(); // Close all client connections
            }

            if (serverSocket != null) {
                serverSocket.close(); // Close the server socket
            }

            System.out.println("Server stopped.");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "An error occurred while stopping the server", e);
        }
    }

    private static synchronized void restartActiveCheckTimer() {
        if (activeCheckTask != null) {
            activeCheckTask.cancel(false); // Cancel current scheduled task
        }
        // Start periodic active member check
        activeCheckTask = scheduler.scheduleAtFixedRate(ChatServer::checkActiveMembers, 120, 120,
                TimeUnit.SECONDS);
    }

    private static void logMessage(String message) {
        if (!message.startsWith("TYPING:") && !message.startsWith("TYPING_END:") &&
                !message.startsWith("REQUEST_CHAT_HISTORY")) {
            try (FileWriter fw = new FileWriter(LOG_FILE, true);
                 BufferedWriter bw = new BufferedWriter(fw);
                 PrintWriter out = new PrintWriter(bw)) {
                out.println(message);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "An error occurred while trying to load message history", e);
            }
        }
    }

    // Handles each connected client in a separate thread
    public static class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;
        private String clientID;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(socket.getOutputStream(), true);

                // Ensure the user selects a unique ID
                while (true) {
                    clientID = reader.readLine();
                    synchronized (activeIDs) {
                        if (!activeIDs.contains(clientID)) {
                            activeIDs.add(clientID);
                            writer.println("ID_ACCEPTED");
                            break;
                        } else {
                            writer.println("ID_EXISTS"); // Prompt client for a new ID
                        }
                    }
                }

                System.out.println(clientID + " has connected.");

                boolean isFirstClient = false;
                synchronized (clients) {
                    clients.add(this); // Add client to the list
                    if (coordinator == null) { // Assign the first client as coordinator
                        coordinator = this;
                        isFirstClient = true;
                        writer.println("COORDINATOR");  // Send coordinator message
                    }
                }

                // Broadcast the user's online status
                broadcast("STATUS:" + clientID + ":online");

                broadcast(clientID + " [" + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() +
                        "] has joined the chat" + (isFirstClient ? " (Coordinator)" : ""));

                // Send the current statuses to the new client
                sendCurrentUserStatuses();

                if (!isFirstClient && coordinator != null) {
                    sendMessage("The current coordinator is: " + coordinator.getClientID() + " [" +
                            coordinator.socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + "]");
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "An error occurred while handling the clients on the server", e);
            }
        }

        // Method to send all current statuses to the new client
        private void sendCurrentUserStatuses() {
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    if (client != this) { // Send the status of other clients
                        this.sendMessage("STATUS:" + client.clientID + ":online");
                    }
                }
                // Send the new client's own status
                this.sendMessage("STATUS:" + this.clientID + ":online");
            }
        }

        public String getClientID() {
            return clientID;
        }

        public void sendMessage(String message) {
            writer.println(message);
        }

        // Main execution loop for each client
        public void run() {
            try {
                String message;
                while ((message = reader.readLine()) != null) {
                    if (message.equals("ACTIVE_CHECK")) {
                        if (this == coordinator) {
                            restartActiveCheckTimer();
                            checkActiveMembers();
                            sendMessage("Active check restarted, next check in 120 seconds.");
                        } else {
                            sendMessage("Only the coordinator can request an active check.");
                        }
                        continue;
                    } else if (message.equals("REQUEST_MEMBER_LIST")) {
                        sendMemberList();
                        continue;
                    } else if (message.startsWith("@")) {
                        sendPrivateMessage(message);
                        continue;
                    } else if (message.startsWith("CHANGE_ID:")) {
                        String newId = message.substring(10).trim();
                        handleIDChange(newId);
                        continue;
                    }  else if (message.startsWith("REACTION:")) {
                        handleReaction(message);
                        continue;
                    } else if (message.startsWith("#") && handleAutoReply(message.substring(1))) {
                        continue;
                    } else if (message.startsWith("TYPING:")) {
                        String[] parts = message.split(":");
                        String userId = parts[1];
                        broadcast("TYPING:" + userId + ":typing");
                        continue;
                    } else if (message.startsWith("TYPING_END:")) {
                        String userId = message.substring(11);
                        broadcast("TYPING_END:" + userId); // Notify all users about typing end
                        continue;
                    } else if (message.startsWith("EDIT_MESSAGE:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length < 3) return;

                        String oldMessage = parts[1].trim();
                        String newMessage = parts[2].trim();

                        boolean updated = false;
                        synchronized (messages) {
                            for (Map.Entry<Integer, String> entry : messages.entrySet()) {
                                if (entry.getValue().equals(oldMessage)) {
                                    messages.put(entry.getKey(), newMessage);
                                    updated = true;
                                    break;
                                }
                            }
                        }

                        if (updated) {
                            broadcast("EDIT_MESSAGE:" + oldMessage + ":" + newMessage, this);
                        }
                    } else if (message.startsWith("DELETE_MESSAGE:")) {
                        String messageToDelete = message.substring(14).trim();
                        synchronized (messages) {
                            messages.values().removeIf(value -> value.equals(messageToDelete));
                        }
                        broadcast("DELETE_MESSAGE:" + messageToDelete, this);
                    } else if (message.equals("REQUEST_CHAT_HISTORY")) {
                        sendChatHistory();
                    }

                    broadcast(message);
                }
            } catch (IOException e) {
                System.out.println(clientID + " disconnected.");
            } finally {
                closeConnection();
            }
        }

        private void sendChatHistory() {
            try (BufferedReader br = new BufferedReader(new FileReader(LOG_FILE))) {
                StringBuilder historyBuilder = new StringBuilder("CHAT_HISTORY:");
                String line;
                while ((line = br.readLine()) != null) {
                    historyBuilder.append(line).append("\n");
                }
                writer.println(historyBuilder);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "An error occurred while sending the chat history from the server", e);
            }
        }

        private boolean handleAutoReply(String message) {
            triggers.put(Pattern.compile("\\b(weather|forecast|temperature|rain|snow|sun|storm|cloud|wind|" +
                                    "humidity|forecasting|forecasted|climate|barometer|precipitation)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Need a forecast? Check Google Weather! ☁️",
                            "Weather changes fast! Try BBC Weather! 🌦️",
                            "Want to know today’s temperature? Just ask! 🌡️",
                            "Curious about the weather? Look it up on Weather.com! 🌤️",
                            "Planning an outing? Don't forget to check AccuWeather! 🌧️",
                            "Wondering if it's going to rain? Check Weather Underground! 🌦️",
                            "Need a weather update? Try the Weather Channel! 🌞",
                            "Want to see the forecast? Check out your local news station! 🌩️",
                            "Is it snowing? Find out on Snow-Forecast.com! ❄️",
                            "Looking for the latest weather news? Try MeteoGroup! 🌪️"));

            triggers.put(Pattern.compile("\\b(sports|sport|score|scoring|match|game|games|team|teams|football|" +
                                    "tennis|ping-pong|volleyball|swimming|basketball|cricket|rugby|baseball|hockey|" +
                                    "boxing|golf|athletics|soccer|championship|olympics|track|field)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Looking for the latest sports news? Check ESPN or Sky Sports! 🏅",
                            "Want to know the score? Try FlashScore or Livescore! 🏆",
                            "Interested in sports updates? Check out BBC Sport or Yahoo Sports!",
                            "Need to know the match schedule? Try the official league websites!",
                            "Want to catch a game? Try streaming on DAZN or NBC Sports!"));

            triggers.put(Pattern.compile("\\b(time|clock|hour|minute|second|watch|alarm|schedule|deadline|timer" +
                            "|timezone|sunrise|sunset|moment|clockwise|countdown)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Check your device’s clock ⏰!",
                            "Time flies! What do you need it for? 🕰️",
                            "Looking for the time? It's always now!",
                            "Curious about the time? Look at the wall! 🕒",
                            "Time is precious! Make every second count! ⏳",
                            "Need the current time? Try World Clock! 🕑",
                            "Want to know the exact time? Check Time.is! 🕔",
                            "Looking for a timer? Try your phone’s built-in clock! ⏲️",
                            "Want to set an alarm? Use your alarm clock! 🕓",
                            "Wondering what time it is? Just ask! 🕟"));

            triggers.put(Pattern.compile("\\b(food|restaurant|recipe|meal|nutrition|cook|dine|eating|cooking|" +
                                    "ingredient|menu|gourmet|dish|mealprep|foodie|vegetarian|vegan|bakery|snack)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Hungry? Check out recipes on AllRecipes! 🍔",
                            "Looking for a restaurant? Try Yelp or Zomato! 🍽️",
                            "Need a quick meal idea? Check Tasty or Food Network!",
                            "Want to order food? Try UberEats or DoorDash! 🍕",
                            "Curious about food nutrition? Check MyFitnessPal or CalorieKing!"));

            triggers.put(Pattern.compile("\\b(hello|hi|hey|greetings|hi there|hey there|hello there|good day" +
                            "|sup|hiya|g'day|howdy|yo|greetings friend|what's up)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Hey there! 😊 How can I help?",
                            "Hello! Hope you're having a great day!",
                            "Hi! What’s on your mind?",
                            "Greetings! How can I assist you today?",
                            "Hey! Ready to chat?",
                            "Hiya! What can I do for you?",
                            "Hello there! Need some help?",
                            "Hey! How's it going?",
                            "Hi! What’s up?",
                            "Good day! How can I be of service?"));

            triggers.put(Pattern.compile("\\b(fitness|workout|yoga|exercise|health|gym|strength|cardio|training" +
                                    "|running|crossfit|stretching|fit|fitnessjourney|healthyliving|wellness|recovery|" +
                                    "pushup|squat|weightlifting)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want to stay fit? Try a workout on Nike Training Club or Fitbit! 🏋️‍♂️",
                            "Looking for fitness tips? Check out bodybuilding.com or Men’s Health!",
                            "Need a yoga session? Try Yoga with Adriene on YouTube!",
                            "Want to track your runs? Use Strava or Runkeeper! 🏃",
                            "Need a fitness plan? Try MyFitnessPal or JEFIT!"));

            triggers.put(Pattern.compile("\\b(bye|goodbye|see you|later|take care|farewell|catch you later|" +
                            "peace out|see ya|good night|have a good day|until next time|talk soon|adios|ciao)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Goodbye! Have a wonderful day! 👋",
                            "See you soon! Take care!",
                            "Farewell! Hope to chat again!",
                            "Bye! Stay safe and take care!",
                            "Adios! Looking forward to our next conversation!",
                            "See you later! Have a great day!",
                            "Goodbye! Don’t be a stranger!",
                            "Bye! Until next time!",
                            "Take care! See you soon!",
                            "Bye-bye! Have a fantastic day!"));

            triggers.put(Pattern.compile("\\b(travel|trip|vacation|hotel|flight|journey|destination|tour|" +
                                    "tripadvisor|holiday|airport|plane|explore|adventure|getaway|cruise|backpacking|" +
                                    "wanderlust|roadtrip)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Planning a trip? Check out TripAdvisor or Lonely Planet! ✈️",
                            "Looking for travel deals? Try Expedia or Skyscanner!",
                            "Need a hotel booking? Check Booking.com or Hotels.com! 🏨",
                            "Want to find local attractions? Check Google Maps or Yelp!",
                            "Curious about travel advisories? Visit the government travel site!"));

            triggers.put(Pattern.compile("\\b(help|support|assist|aid|guide|tips|assist me|help me|need help|" +
                            "can you help|support me|guide me)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("I'm here to help! What do you need assistance with?",
                            "How can I assist you today?",
                            "Help is on the way! What do you need?",
                            "Need a hand? I'm here for you!",
                            "How can I be of service?",
                            "What can I do for you?",
                            "Need help? I'm ready!",
                            "How can I assist you?",
                            "Looking for support? I’m here!",
                            "Need support? Just ask!"));

            triggers.put(Pattern.compile("\\b(finance|money|budget|investment|stock|loan|bank|wealth|income|" +
                                    "savings|interest|financial|credit|debt|funds|tax|financialplanning|" +
                                    "financialadvisor)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want to manage your money? Try Mint or YNAB! 💰",
                            "Looking for investment tips? Check out Investopedia or Motley Fool!",
                            "Need a budget planner? Try EveryDollar or Goodbudget!",
                            "Want to track your expenses? Use PocketGuard or Wally! 💵",
                            "Curious about the stock market? Check Bloomberg or CNBC!"));

            triggers.put(Pattern.compile("\\b(joke|funny|humor|laugh|comedy|pun|hilarious|laughter|chuckle|" +
                            "giggle|wit|sarcasm|silly|jovial|lighthearted)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Why did the scarecrow win an award? Because he was outstanding in his field! 😆",
                            "What do you call fake spaghetti? An impasta! 🍝",
                            "Why don’t skeletons fight each other? They don’t have the guts! 😂",
                            "Want to hear a joke? Why don't scientists trust atoms? Because they make up everything! " +
                                    "🧪",
                            "Looking for a laugh? Here's one: Why did the bicycle fall over? It was two-tired! 🚲",
                            "Why did the computer go to the doctor? Because it had a virus! 🖥️",
                            "How do you organize a space party? You planet! 🪐",
                            "Why don't some couples go to the gym? Because some relationships don't work out! 💔",
                            "What did the ocean say to the beach? Nothing, it just waved! 🌊",
                            "Why don't eggs tell jokes? They'd crack each other up! 🥚"));

            triggers.put(Pattern.compile("\\b(health|doctor|mental|fitness|medical|wellness|therapy|workout|" +
                                    "gym|nutrition|exercise|recovery|healthcare|medication|treatment)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want health tips? Check WebMD or Mayo Clinic! 🏥",
                            "Looking for healthy recipes? Try EatingWell or Healthline!",
                            "Need mental health support? Check BetterHelp or Talkspace! 💬",
                            "Want to track your health? Use Apple Health or Google Fit!",
                            "Curious about medical news? Visit MedlinePlus or HealthDay!"));

            triggers.put(Pattern.compile("\\b(news|update|headlines|breaking|current|report|headline|bulletin" +
                            "|alert|today|coverage|reporting)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want the latest news? Check out Google News or BBC News! 📰",
                            "Looking for updates? Try CNN or The Guardian!",
                            "Stay informed with the latest headlines on Reuters!",
                            "Catch up on the news with NY Times or The Washington Post!",
                            "Get the latest updates on Al Jazeera or Sky News!",
                            "Need breaking news? Check Fox News or NBC News!",
                            "Want to stay updated? Try ABC News or CBS News!",
                            "Looking for top stories? Check out Bloomberg or Financial Times!",
                            "Interested in global news? Try DW News or France 24!",
                            "Looking for tech news? Visit TechCrunch or Wired!"));

            triggers.put(Pattern.compile("\\b(education|study|learn|course|university|school|degree|student|" +
                                    "studyguide|exam|class|learning|academic|scholar|tutor|textbook)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want to learn something new? Check out Coursera or Udemy! 🎓",
                            "Looking for online courses? Try edX or Khan Academy!",
                            "Need study resources? Check Quizlet or Chegg! 📚",
                            "Want to improve your skills? Try LinkedIn Learning or Skillshare!",
                            "Curious about college tips? Visit CollegeBoard or Niche!"));

            triggers.put(Pattern.compile("\\b(music|song|listen|album|band|playlist|concert|melody|rhythm|" +
                            "lyrics|artist|instrument|musician|composer|musicvideo|tune)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Music is life! 🎵 Try Spotify!",
                            "Want to discover new tunes? Try Apple Music!",
                            "Listening to music is therapy! Check YouTube Music!",
                            "Need a music fix? Amazon Music has you covered!",
                            "Tune in to Pandora for some great tracks!",
                            "Want to listen to new songs? Try SoundCloud!",
                            "Looking for classics? Try iHeartRadio!",
                            "Need a playlist for your mood? Check Deezer!",
                            "Want to explore indie music? Try Bandcamp!",
                            "Looking for live radio? Try TuneIn!"));

            triggers.put(Pattern.compile("\\b(technology|tech|gadget|software|programming|coding|developer|" +
                                    "innovation|AI|app|device|startup|electronics|robotics|web|cybersecurity)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want tech news? Check TechCrunch or Wired! 🖥️",
                            "Looking for gadget reviews? Try CNET or The Verge!",
                            "Need software tips? Check How-To Geek or Lifehacker! 💻",
                            "Want to stay updated on AI? Visit OpenAI or AI News!",
                            "Curious about programming? Check Stack Overflow or GitHub!"));

            triggers.put(Pattern.compile("\\b(movie|film|watch|cinema|flick|stream|show|episode|theater|binge" +
                            "|blockbuster|director|actor|comedy|drama)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Looking for a good movie? Check IMDb or Netflix recommendations! 🎬",
                            "Need a movie suggestion? Try Rotten Tomatoes!",
                            "Want to watch something? Hulu and Disney+ have great choices!",
                            "In the mood for a film? Amazon Prime Video is a great option!",
                            "Watch the latest flicks on HBO Max or Peacock!",
                            "Need a film recommendation? Try Fandango!",
                            "Looking for movie reviews? Check out Metacritic!",
                            "Want to stream movies? Try Vudu!",
                            "Interested in documentaries? Visit CuriosityStream!",
                            "Want to catch up on TV shows? Try Showtime or Starz!"));

            triggers.put(Pattern.compile("\\b(fashion|style|clothing|outfit|trend|accessory|wear|shoes|apparel" +
                                    "|beauty|dress|chic|model|runway)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want fashion tips? Check Vogue or GQ! 👗",
                            "Looking for style inspiration? Try Pinterest or Instagram!",
                            "Need outfit ideas? Check Lookbook or Polyvore!",
                            "Want to shop online? Try ASOS or Zara! 🛍️",
                            "Curious about fashion trends? Visit Fashionista or Who What Wear!"));

            triggers.put(Pattern.compile("\\b(quote|inspire|motivate|wisdom|motto|encourage|success|believe|" +
                            "dream|achievement|goal|hope|positivity|ambition)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("“Believe you can and you're halfway there.” – Theodore Roosevelt",
                            "“Your time is limited, so don’t waste it living someone else’s life.” – Steve Jobs",
                            "“The only limit to our realization of tomorrow is our doubts of today.” – FDR",
                            "“The best way to predict the future is to create it.” – Peter Drucker",
                            "“Success is not the key to happiness. Happiness is the key to success.” – Albert " +
                                    "Schweitzer",
                            "“The journey of a thousand miles begins with one step.” – Lao Tzu",
                            "“You miss 100% of the shots you don’t take.” – Wayne Gretzky",
                            "“Hardships often prepare ordinary people for an extraordinary destiny.” – C.S. Lewis",
                            "“Don’t watch the clock; do what it does. Keep going.” – Sam Levenson",
                            "“The only way to do great work is to love what you do.” – Steve Jobs"));

            triggers.put(Pattern.compile("\\b(literature|book|reading|novel|author|fiction|story|library|poetry" +
                                    "|chapter|paper|bookworm|bookstore|literary)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want book recommendations? Check Goodreads or BookBub! 📚",
                            "Looking for eBooks? Try Kindle or Project Gutenberg!",
                            "Need a book summary? Check SparkNotes or CliffsNotes!",
                            "Want to join a book club? Try Book Riot or Reader’s Circle!",
                            "Curious about bestsellers? Visit NY Times Best Sellers or Amazon!"));

            triggers.put(Pattern.compile("\\b(programming|code|developer|coding|debug|software|algorithm|tech|" +
                            "python|javascript|html|css|computer|open-source)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Coding is fun! Need help? Try Stack Overflow or GitHub! 💻",
                            "Programming is an art! Keep practicing!",
                            "Want to learn coding? Check out freeCodeCamp!",
                            "Developers unite! Visit CodePen for inspiration!",
                            "Need coding tips? Try W3Schools or MDN Web Docs!",
                            "Looking for coding challenges? Try HackerRank or LeetCode!",
                            "Want to join a coding community? Try Reddit’s r/programming!",
                            "Need a code editor? Try Visual Studio Code or Sublime Text!",
                            "Interested in open-source projects? Visit GitHub or GitLab!",
                            "Learning a new language? Try Codecademy or Coursera!"));

            triggers.put(Pattern.compile("\\b(diy|craft|home|project|improvement|build|decorate|remodel|" +
                                    "handmade|repair|ideas|design|tutorial)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want DIY ideas? Check Pinterest or DIY Network! 🔨",
                            "Looking for craft projects? Try Craftsy or Instructables!",
                            "Need home improvement tips? Check This Old House or Home Depot!",
                            "Want to build something? Try Make: Magazine or Woodworking!",
                            "Curious about gardening? Visit Gardeners.com or RHS!"));

            triggers.put(Pattern.compile("\\b(random|surprise|guess|funny|odd|weird|fact|trivia|bizarre|" +
                            "interesting)\\b", Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Here's something random: Did you know honey never spoils? 🍯",
                            "Fun fact: Bananas are berries, but strawberries aren’t! 🍌🍓",
                            "Surprise! A group of flamingos is called a 'flamboyance'! 🦩",
                            "Guess what? Octopuses have three hearts! 🐙",
                            "Random thought: A day on Venus is longer than a year on Venus! 🌌",
                            "Did you know? The Eiffel Tower can be 15 cm taller during the summer! 🗼",
                            "Fun fact: A bolt of lightning contains enough energy to toast 100,000 slices of bread! ⚡",
                            "Did you know? The shortest war in history lasted 38 minutes! 🕒",
                            "Random fact: A single strand of Spaghetti is called a “Spaghetto” 🍝",
                            "Guess what? There are more stars in the universe than grains of sand on all the world’s" +
                                    " beaches!"));

            triggers.put(Pattern.compile("\\b(pets|dog|cat|animal|training|care|adopt|petlover|veterinary|" +
                                    "health|species|petshop|petcare)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want pet care tips? Check PetMD or ASPCA! 🐶",
                            "Looking for pet products? Try Chewy or Petco!",
                            "Need pet training advice? Check The Spruce Pets or Cesar’s Way!",
                            "Want to adopt a pet? Try Petfinder or Adopt-a-Pet! 🐱",
                            "Curious about pet health? Visit VCA Hospitals or Banfield!"));

            triggers.put(Pattern.compile("\\b(gaming|game|esports|score|tournament|play|console|gamer|platform|" +
                                    "level|pc|xbox|switch|mobile)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want gaming news? Check IGN or GameSpot! 🎮",
                            "Looking for game reviews? Try Metacritic or Kotaku!",
                            "Need game guides? Check GameFAQs or Prima Games!",
                            "Want to watch gaming streams? Try Twitch or YouTube Gaming!",
                            "Curious about esports? Visit ESL or Major League Gaming!"));

            triggers.put(Pattern.compile("\\b(home|decor|interior|furniture|design|apartment|livingroom|style|" +
                                    "remodel|house|modern|organization|renovation)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want home decor ideas? Check Houzz or Apartment Therapy! 🏡",
                            "Looking for interior design tips? Try Elle Decor or Architectural Digest!",
                            "Need furniture shopping? Check IKEA or Wayfair!",
                            "Want to organize your space? Try The Container Store!",
                            "Curious about home improvement? Visit Lowe’s or Home Depot!"));

            triggers.put(Pattern.compile("\\b(parenting|baby|child|family|kids|motherhood|fatherhood|mom|dad|" +
                                    "school|children|health|pregnancy|education)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want parenting tips? Check BabyCenter or Parenting.com! 👶",
                            "Looking for child development info? Try CDC or KidsHealth!",
                            "Need parenting support? Check What to Expect or NCT!",
                            "Want to find activities for kids? Try PBS Kids or Highlights!",
                            "Curious about family health? Visit FamilyDoctor.org or WebMD!"));

            triggers.put(Pattern.compile("\\b(beauty|skin|hair|makeup|cosmetic|skincare|beautycare|haircare|" +
                                    "glam|beautytips|makeuptutorial)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want beauty tips? Check Allure or Glamour! 💄",
                            "Looking for skincare advice? Try Dermstore or Paula’s Choice!",
                            "Need makeup tutorials? Check YouTube or Sephora!",
                            "Want to find beauty products? Try Ulta or Beauty Bay!",
                            "Curious about hair care? Visit NaturallyCurly or Hair.com!"));

            triggers.put(Pattern.compile("\\b(automobile|car|vehicle|maintenance|reviews|auto|mechanic|engine|" +
                                    "repair|transport|gas|fuel|sedan|motor)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want car reviews? Check Edmunds or Car and Driver! 🚗",
                            "Looking for car maintenance tips? Try AutoZone or Pep Boys!",
                            "Need to buy a car? Check Kelley Blue Book or Autotrader!",
                            "Want to sell your car? Try CarMax or Cars.com!",
                            "Curious about car news? Visit MotorTrend or Top Gear!"));

            triggers.put(Pattern.compile("\\b(history|museum|event|archive|world|timeline|civilization|heritage" +
                                    "|ancient|chronicles|past|historylesson|documentary)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want to learn about history? Check History.com or Smithsonian! 📜",
                            "Looking for historical documentaries? Try Netflix or History Vault!",
                            "Need history books? Check Goodreads or Amazon!",
                            "Want to visit historical sites? Try National Geographic or TripAdvisor!",
                            "Curious about world history? Visit BBC History or World History Encyclopedia!"));

            triggers.put(Pattern.compile("\\b(science|experiment|biology|space|research|lab|scientific|physics|" +
                                    "chemistry|discovery|technology|environment|astronomy)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want science news? Check Scientific American or Nature! 🔬",
                            "Looking for science experiments? Try Science Buddies or Exploratorium!",
                            "Need science facts? Check National Geographic or Science Alert!",
                            "Want to watch science videos? Try YouTube or Discovery Channel!",
                            "Curious about space? Visit NASA or Space.com!"));

            triggers.put(Pattern.compile("\\b(art|artist|gallery|painting|inspiration|drawing|sculpture|" +
                                    "modernart|artistic|museum|design|visual)\\b",
                            Pattern.CASE_INSENSITIVE),
                    Arrays.asList("Want art inspiration? Check DeviantArt or Behance! 🎨",
                            "Looking for art tutorials? Try YouTube or Skillshare!",
                            "Need art supplies? Check Blick or Michaels!",
                            "Want to visit art galleries? Try Google Arts & Culture or ArtNet!",
                            "Curious about famous artists? Visit MoMA or The Art Story!"));

            // Check each pattern and respond if a match is found
            for (Map.Entry<Pattern, List<String>> entry : triggers.entrySet()) {
                Matcher matcher = entry.getKey().matcher(message);
                if (matcher.find()) {
                    List<String> possibleResponses = entry.getValue();
                    // Random reply
                    String response = possibleResponses.get(new Random().nextInt(possibleResponses.size()));
                    sendMessage(response);

                    return true;
                }
            }
            return false; // No match found
        }

        private void handleReaction(String message) {
            String[] parts = message.split(":", 3);
            if (parts.length < 3) return; // Invalid reaction message format
            String messageId = parts[1];
            String reaction = parts[2];

            synchronized (messageReactions) {
                messageReactions.computeIfAbsent(messageId, _ -> new ArrayList<>()).add(reaction);
            }

            broadcast("REACTION:" + messageId + ":" + reaction);
        }

        // Handles private messages sent to specific users
        private void sendPrivateMessage(String message) {
            String[] parts = message.split(" ", 2);
            if (parts.length < 2) return; // Invalid private message format
            String recipientID = parts[0].substring(1); // Extract recipient ID from @id
            String privateMessage = "(Private) " + clientID + ": " + parts[1];

            boolean found = false;
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    if (client.getClientID().equals(recipientID)) {
                        client.sendMessage(privateMessage); // Send message only to recipient
                        sendMessage("(Private to " + recipientID + ") " + parts[1]); // Confirmation for sender
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                sendMessage("User " + recipientID + " not found.");
            }
        }

        // Sends a list of active members to the requesting client
        private void sendMemberList() {
            StringBuilder memberList = new StringBuilder("Active Members:\n");
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    memberList.append(client.getClientID())
                              .append(" - ")
                              .append(client.socket.getInetAddress().getHostAddress())
                              .append(":")
                              .append(client.socket.getPort())
                              .append(client == coordinator ? " (Coordinator)" : "")
                              .append("\n");
                }
            }
            sendMessage(memberList.toString());
        }

        // Handles client disconnection and reassigns coordinator
        public void closeConnection() {
            try {
                if (socket != null) socket.close();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "An unknown error occurred while running the server", e);
            }

            synchronized (activeIDs) {
                activeIDs.remove(clientID); // Ensure the new ID is removed
            }
            clients.remove(this);
            broadcast(clientID + " has left the chat.");

            broadcast("STATUS:" + clientID + ":offline");

            if (clients.isEmpty()) {
                clearLogFile();
            }

            stopServerIfEmpty();

            if (this == coordinator && !clients.isEmpty()) {
                coordinator = clients.iterator().next();
                coordinator.sendMessage("You are now the coordinator.");
                broadcast("New coordinator is " + coordinator.getClientID(), coordinator);
            } else if (clients.isEmpty()) {
                coordinator = null;
            }
        }

        private static void clearLogFile() {
            try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE))) {
                writer.print("");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "The request of clearing the log file has produced an unknown error", e);
            }
        }

        public void handleIDChange(String newId) {
            synchronized (activeIDs) {
                if (activeIDs.contains(newId)) {
                    writer.println("ID_EXISTS");
                    return;
                }

                activeIDs.remove(clientID);
                activeIDs.add(newId);
            }

            String oldId = clientID;
            clientID = newId;

            String notification = "User " + oldId + " has changed their ID to " + newId;
            broadcast(notification);
            writer.println("ID_ACCEPTED");

            // Broadcast the status change with the new ID
            broadcast("STATUS:" + oldId + ":offline");
            broadcast("STATUS:" + newId + ":online");
        }

        private void broadcast(String message, ClientHandler excludeClient) {
            logMessage(message);
            synchronized (clients) {
                for (ClientHandler client : clients) {
                    // Broadcast the message to all clients (including the sender)
                    if (excludeClient == null || client != excludeClient) {
                        client.writer.println(message);
                    }
                }
            }
        }

        // Overloaded version for broadcasting to all clients
        private void broadcast(String message) {
            broadcast(message, null); // Calls the other method, passing null to send to all clients
        }
    }
}
