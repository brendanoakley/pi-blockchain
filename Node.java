import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

// =============================================================================
//  Node.java
//  Peer-to-peer networking layer.
//  Each Node wraps a Blockchain instance and exposes a TCP server so that
//  other nodes can push transactions and mined blocks to it.
//
//  Wire protocol (newline-terminated, pipe-delimited):
//    TRANSACTION|sender:receiver:amount:timestamp
//    BLOCK|index:timestamp:prevHash:hash:nonce:minerNodeId:txCSV
//    PEER|host:port
//    PING|nodeId
//
//  txCSV format: sender,receiver,amount,timestamp  (semicolon-separated rows)
// =============================================================================

public class Node {

    private final String          nodeId;
    private final int             port;
    private final Blockchain      blockchain;
    private final List<String>    peers;          // "host:port"
    private final ExecutorService threadPool;

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    // -------------------------------------------------------------------------
    //  Construction & lifecycle
    // -------------------------------------------------------------------------

    public Node(String nodeId, int port) {
        this(nodeId, port, new Blockchain());
    }

    /** Constructor used when loading a persisted chain from disk. */
    public Node(String nodeId, int port, Blockchain blockchain) {
        this.nodeId     = nodeId;
        this.port       = port;
        this.blockchain = blockchain;
        this.peers      = new CopyOnWriteArrayList<>();
        this.threadPool = Executors.newCachedThreadPool();
    }

    /**
     * Start the TCP listener on a background thread.
     * Non-blocking — returns immediately.
     */
    public void start() {
        running = true;
        threadPool.submit(this::listen);
        System.out.println("[" + nodeId + "] Node started on port " + port);
    }

    /** Shut down the listener and thread pool gracefully. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) { }
        threadPool.shutdown();
        System.out.println("[" + nodeId + "] Node stopped.");
    }

    // -------------------------------------------------------------------------
    //  TCP server
    // -------------------------------------------------------------------------

    /** Accept loop — runs on a dedicated background thread. */
    private void listen() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    // Handle each incoming connection on its own thread.
                    threadPool.submit(() -> handleConnection(client));
                } catch (SocketException e) {
                    if (running) {
                        System.out.println("[" + nodeId + "] Socket error: " + e.getMessage());
                    }
                    // If !running the server was deliberately closed — exit quietly.
                }
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("[" + nodeId + "] Could not start server on port " + port
                        + ": " + e.getMessage());
            }
        }
    }

    /** Read a complete message from the socket then dispatch it. */
    private void handleConnection(Socket socket) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            String message = sb.toString().trim();
            if (!message.isEmpty()) {
                processMessage(message);
            }
        } catch (IOException e) {
            System.out.println("[" + nodeId + "] Error reading from connection: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    // -------------------------------------------------------------------------
    //  Message dispatch
    // -------------------------------------------------------------------------

    private void processMessage(String message) {
        // Split only on the first pipe so the payload may itself contain pipes.
        int sep = message.indexOf('|');
        if (sep < 0) return;

        String type    = message.substring(0, sep);
        String payload = message.substring(sep + 1);

        switch (type) {
            case "TRANSACTION": handleIncomingTransaction(payload); break;
            case "BLOCK":       handleIncomingBlock(payload);       break;
            case "PEER":        addPeer(payload);                   break;
            case "PING":
                System.out.println("[" + nodeId + "] PING from " + payload);
                break;
            default:
                System.out.println("[" + nodeId + "] Unknown message type: " + type);
        }
    }

    // -------------------------------------------------------------------------
    //  Receive helpers
    // -------------------------------------------------------------------------

    /**
     * Parse and validate an incoming transaction.
     * Format: sender:receiver:amount:timestamp
     */
    private void handleIncomingTransaction(String data) {
        String[] parts = data.split(":", 4);
        if (parts.length < 4) {
            System.out.println("[" + nodeId + "] Malformed transaction: " + data);
            return;
        }
        try {
            String sender    = parts[0];
            String receiver  = parts[1];
            double amount    = Double.parseDouble(parts[2]);
            long   timestamp = Long.parseLong(parts[3]);

            Blockchain.Transaction tx =
                    new Blockchain.Transaction(sender, receiver, amount, timestamp);
            boolean ok = blockchain.addTransaction(tx);
            System.out.println("[" + nodeId + "] Incoming TX " + tx
                    + (ok ? " [ACCEPTED]" : " [REJECTED]"));
        } catch (NumberFormatException e) {
            System.out.println("[" + nodeId + "] Could not parse transaction: " + data);
        }
    }

    /**
     * Parse, validate, and (if valid) append an incoming block.
     * Format: index:timestamp:prevHash:hash:nonce:minerNodeId:txCSV
     * txCSV row: sender,receiver,amount,timestamp  (rows separated by ';')
     */
    private void handleIncomingBlock(String data) {
        // Split into exactly 7 parts — txCSV may contain commas and semicolons.
        String[] parts = data.split(":", 7);
        if (parts.length < 7) {
            System.out.println("[" + nodeId + "] Malformed block message.");
            return;
        }
        try {
            int    index       = Integer.parseInt(parts[0]);
            long   timestamp   = Long.parseLong(parts[1]);
            String prevHash    = parts[2];
            String hash        = parts[3];
            int    nonce       = Integer.parseInt(parts[4]);
            String minerNodeId = parts[5];
            String txCSV       = parts[6];

            List<Blockchain.Transaction> txList = parseTxCSV(txCSV);

            // Reconstruct the block using the received metadata.
            Blockchain.Block block =
                    new Blockchain.Block(index, txList, prevHash, minerNodeId);
            block.setTimestamp(timestamp);
            block.setNonce(nonce);
            block.setHash(hash);

            boolean ok = blockchain.addMinedBlock(block);
            System.out.println("[" + nodeId + "] Incoming Block #" + index + " from "
                    + minerNodeId + (ok ? " [ACCEPTED]" : " [REJECTED]"));
        } catch (Exception e) {
            System.out.println("[" + nodeId + "] Error parsing block: " + e.getMessage());
        }
    }

    /** Parse a semicolon-separated list of "sender,receiver,amount,timestamp" rows. */
    private List<Blockchain.Transaction> parseTxCSV(String csv) {
        List<Blockchain.Transaction> list = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return list;
        for (String row : csv.split(";")) {
            String[] f = row.split(",", 4);
            if (f.length < 4) continue;
            try {
                list.add(new Blockchain.Transaction(
                        f[0], f[1],
                        Double.parseDouble(f[2]),
                        Long.parseLong(f[3])));
            } catch (NumberFormatException ignored) { }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    //  Broadcast helpers
    // -------------------------------------------------------------------------

    /** Broadcast a transaction to every known peer. */
    public void broadcastTransaction(Blockchain.Transaction tx) {
        String msg = "TRANSACTION|" + tx.getSender() + ":"
                + tx.getReceiver() + ":"
                + tx.getAmount()   + ":"
                + tx.getTimestamp();
        broadcast(msg);
    }

    /** Broadcast a mined block to every known peer. */
    public void broadcastBlock(Blockchain.Block block) {
        // Build txCSV
        StringBuilder txCSV = new StringBuilder();
        List<Blockchain.Transaction> txs = block.getTransactions();
        for (int i = 0; i < txs.size(); i++) {
            Blockchain.Transaction tx = txs.get(i);
            if (i > 0) txCSV.append(';');
            txCSV.append(tx.getSender()).append(',')
                 .append(tx.getReceiver()).append(',')
                 .append(tx.getAmount()).append(',')
                 .append(tx.getTimestamp());
        }

        String msg = "BLOCK|"
                + block.getIndex()       + ":"
                + block.getTimestamp()   + ":"
                + block.getPreviousHash() + ":"
                + block.getHash()        + ":"
                + block.getNonce()       + ":"
                + block.getMinerNodeId() + ":"
                + txCSV;
        broadcast(msg);
    }

    /** Announce our address to every known peer so they can add us back. */
    public void announceToNetwork() {
        broadcast("PEER|localhost:" + port);
    }

    /** Send a message to every peer. Failures are logged but do not abort. */
    private void broadcast(String message) {
        for (String peer : peers) {
            sendToPeer(peer, message);
        }
    }

    /** Open a short-lived TCP connection to peer and send message. */
    private void sendToPeer(String peer, String message) {
        String[] parts = peer.split(":", 2);
        if (parts.length < 2) return;
        try {
            int peerPort = Integer.parseInt(parts[1]);
            try (Socket socket = new Socket(parts[0], peerPort);
                 PrintWriter writer =
                         new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {
                writer.println(message);
            }
        } catch (Exception e) {
            System.out.println("[" + nodeId + "] Failed to reach peer " + peer
                    + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    //  High-level public API  (used by BlockchainDemo)
    // -------------------------------------------------------------------------

    /**
     * Create a transaction, validate it, add it to the pending pool, and
     * broadcast it to all peers.
     *
     * @return true if the transaction was accepted
     */
    public boolean addTransaction(String sender, String receiver, double amount) {
        Blockchain.Transaction tx =
                new Blockchain.Transaction(sender, receiver, amount);
        boolean ok = blockchain.addTransaction(tx);
        if (ok) broadcastTransaction(tx);
        return ok;
    }

    /**
     * Mine all pending transactions into a new block, then broadcast the
     * block to all peers.
     *
     * @return the new block, or null if there was nothing to mine
     */
    public Blockchain.Block mine() {
        Blockchain.Block block = blockchain.minePendingTransactions(nodeId);
        if (block != null) broadcastBlock(block);
        return block;
    }

    /** Register a peer address ("host:port"). */
    public void addPeer(String address) {
        if (address != null && !address.isEmpty() && !peers.contains(address)) {
            peers.add(address);
            System.out.println("[" + nodeId + "] Peer added: " + address);
        }
    }

    /** Print a summary of this node's current state. */
    public void printStatus() {
        System.out.println();
        System.out.println("=== Node Status: " + nodeId + " ===");
        System.out.println("  Listening port     : " + port);
        System.out.println("  Peers              : " + (peers.isEmpty() ? "(none)" : peers));
        System.out.println("  Chain length       : " + blockchain.getLength() + " block(s)");
        System.out.println("  Pending txs        : " + blockchain.getPendingTransactions().size());
        System.out.println("  Chain valid        : " + blockchain.isChainValid());
    }

    // --- Getters --------------------------------------------------------------
    public String       getNodeId()    { return nodeId;     }
    public int          getPort()      { return port;       }
    public Blockchain   getBlockchain(){ return blockchain; }
    public List<String> getPeers()     { return peers;      }
    public boolean      isRunning()    { return running;    }
}
