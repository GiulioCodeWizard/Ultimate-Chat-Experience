package myapp.chat;

import java.io.*;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;


public class ChatServerTest {
    private int port;
    private ServerSocket serverSocket;
    private static final Logger logger = Logger.getLogger(ChatServerTest.class.getName()); // Catch exception errors

    @BeforeEach
    public void setUp() throws Exception {
        // Create a temporary server socket using an ephemeral port
        serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();
        ChatServer.running = true; // Ensure the server is running before each test

        // Start server thread to accept clients
        new Thread(() -> {
            try {
                while (ChatServer.running) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(new ChatServer.ClientHandler(clientSocket)).start();
                }
            } catch (IOException e) {
                if (ChatServer.running) {
                    logger.log(Level.SEVERE, "An error occurred", e);
                }
            }
        }).start();

        System.out.println("Server started on port: " + port);
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Stop the server and close the socket after each test
        ChatServer.stopServer();
        serverSocket.close();
        System.out.println("Server stopped.");
    }

    @Nested
    public class UniqueIdTests {
        @Test
        public void testUniqueIdAssignment() throws Exception {
            System.out.println("Connecting first client...");
            Socket client1 = new Socket("localhost", port);
            BufferedReader in1 = new BufferedReader(new InputStreamReader(client1.getInputStream()));
            PrintWriter out1 = new PrintWriter(client1.getOutputStream(), true);

            out1.println("Giulio");
            client1.setSoTimeout(5000);  // Prevents blocking on readLine()
            assertEquals("ID_ACCEPTED", in1.readLine());
            assertEquals("COORDINATOR", in1.readLine());

            System.out.println("Connecting second client...");
            Socket client2 = new Socket("localhost", port);
            BufferedReader in2 = new BufferedReader(new InputStreamReader(client2.getInputStream()));
            PrintWriter out2 = new PrintWriter(client2.getOutputStream(), true);

            out2.println("Giulio");
            client2.setSoTimeout(5000);
            assertEquals("ID_EXISTS", in2.readLine());

            out2.println("Jude");
            assertEquals("ID_ACCEPTED", in2.readLine());

            // Cleanup
            client1.close();
            client2.close();
        }
    }
}
