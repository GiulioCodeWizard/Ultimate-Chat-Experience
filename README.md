# Ultimate-Chat-Experience
## Group-Based Communication in Java

🚀 **Ultimate-Chat-Experience** is a real-time, distributed network chat application that enables seamless communication between multiple users. A designated coordinator manages the group, ensuring smooth interactions. If the coordinator leaves, another user automatically takes over.

---

## ✨ Features

### 💬 Messaging
- **@Username Message** → Send private messages to specific users.
- **Broadcast Chat** → Communicate with everyone in the network.
- **Edit/Delete Messages** → Double-click your message to modify or remove it.
- **Emoji Reactions** → Right-click on a message to add reactions.
- **AI Commands** → Use `#keyword` (e.g., `#weather`) for AI-generated responses.

### 🛠 User Controls
- **Request Members** → View active users, including their name, IP, and coordinator status.
- **Update ID** → Change your username (valid & unique names only).
- **Status Panel** → See who's online or offline in real time.
- **History Log** → Conversations are stored in `chat_log.txt` and reset when the server shuts down.

### 📎 Multimedia Sharing
- **Express with Emojis** → Add fun to your messages.
- **Attach Files** → Send documents, images, and other files effortlessly.
- **Voice Messages** → Record and share voice clips instantly.

### 🔴 Exit Anytime
- Hit **Quit** when you're ready to leave the chat.

---

## 🛠 Installation & Setup

### 📌 Prerequisites
Ensure you have the following installed:
- **Java 11+** (JDK)
- **Git** (optional, for cloning the repository)

### 📥 Clone the Repository
```sh
 git clone https://github.com/your-repo/Ultimate-Chat-Experience.git
 cd Ultimate-Chat-Experience
```

### 🔧 Compile & Run
#### Start the Server:
```sh
javac Server.java
java Server
```

#### Start a Client:
```sh
javac Client.java
java Client
```

🔄 **Multiple clients** can connect to the server by running the `Client` application separately.

---

## 🚀 Usage Guide
1. **Launch the Server** → Run `Server.java` to start hosting the chat.
2. **Connect Clients** → Run `Client.java` on multiple machines to join the network.
3. **Start Chatting** → Send messages, use commands, and share media effortlessly!

---

## 🛡 Security & Privacy
- **End-to-End Communication** → Messages are transmitted securely within the network.
- **Data Privacy** → Chat history is stored locally and resets upon server shutdown.

---

## 🤝 Contributing
We welcome contributions! To contribute:
1. Fork the repository
2. Create a new branch (`feature-branch`)
3. Commit your changes
4. Push to your branch and create a Pull Request

---

## 📝 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

💡 **Enjoy chatting, stay connected, and have fun!** 🚀
