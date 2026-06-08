import java.util.*;
import java.util.concurrent.*;

// =============================================================================
//  BlockchainAutoDemo.java
//  Autonomous 4-node blockchain demo — no manual input needed.
//
//  Run with:  java BlockchainAutoDemo
//
//  Starts MacBook, Pi1, Pi2, Pi3 as real TCP nodes on localhost:5001-5004.
//  Each node independently and randomly:
//    - Sends random transactions to other nodes
//    - Competes to mine the next block (first to solve wins, others are orphaned)
//    - Occasionally attempts a spoof (rejected by validation)
//
//  Press Ctrl+C to stop.
// =============================================================================

public class BlockchainAutoDemo {

    static final String[] IDS   = {"MacBook", "Pi1", "Pi2", "Pi3"};
    static final int[]    PORTS = {5001, 5002, 5003, 5004};
    static final Random   RAND  = new Random();

    static Node[] nodes    = new Node[4];
    static long   start;

    // =========================================================================
    //  Entry point
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        start = System.currentTimeMillis();
        printHeader();

        // --- Boot all 4 nodes ------------------------------------------------
        for (int i = 0; i < 4; i++) {
            nodes[i] = new Node(IDS[i], PORTS[i]);
            nodes[i].start();
        }
        Thread.sleep(600);  // give servers time to bind

        // --- Fully connect: every node knows every other node ----------------
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i != j) nodes[i].addPeer("localhost:" + PORTS[j]);
            }
        }
        log("NETWORK", "All 4 nodes connected — starting autonomous demo");
        log("NETWORK", "Difficulty=" + Blockchain.Block.DIFFICULTY
                + "  Mining reward=" + (int) Blockchain.MINING_REWARD + " coins/block");
        printBalances();

        // --- One autonomous thread per node ----------------------------------
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            final Node n = nodes[i];
            pool.submit(() -> runNode(n));
        }

        // Shut down cleanly on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[NETWORK] Shutting down...");
            for (Node n : nodes) n.stop();
        }));

        Thread.currentThread().join();  // block main thread forever
    }

    // =========================================================================
    //  Autonomous node loop
    // =========================================================================

    static void runNode(Node node) {
        while (true) {
            try {
                // Wait a random interval before the next action (2–7 seconds)
                Thread.sleep(2000 + RAND.nextInt(5000));

                int roll = RAND.nextInt(10);

                if (roll < 4) {
                    doTransaction(node);   // 40 % — send coins
                } else if (roll < 7) {
                    doMine(node);          // 30 % — race to mine
                } else if (roll < 9) {
                    doSpoof(node);         // 20 % — attempt double-spend
                } else {
                    printBalances();       // 10 % — status snapshot
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // =========================================================================
    //  Actions
    // =========================================================================

    /** Send a random valid transaction to another node. */
    static void doTransaction(Node node) {
        String sender  = node.getNodeId();
        double balance = node.getBlockchain().getBalance(sender);

        if (balance < 20) {
            log(sender, "Balance too low (" + fmt(balance) + " coins) — skipping tx");
            return;
        }

        String receiver = randomOther(sender);
        // Send between 10 coins and 25 % of current balance
        double max    = Math.max(10, balance * 0.25);
        double amount = Math.round(10 + RAND.nextDouble() * (max - 10));
        amount = Math.min(amount, balance - 10);

        log(sender, "Sending " + fmt(amount) + " coins → " + receiver);
        node.addTransaction(sender, receiver, amount);
    }

    /**
     * Race to mine the next block.
     * Uses mineBlockOnly() so multiple nodes can compete simultaneously.
     * The first to call addMinedBlock() wins; the rest are orphaned.
     */
    static void doMine(Node node) {
        Blockchain bc = node.getBlockchain();

        if (bc.getPendingTransactions().isEmpty()) {
            log(node.getNodeId(), "Nothing to mine — pending pool is empty");
            return;
        }

        int targetIdx = bc.getLength();
        log(node.getNodeId(), "⛏  Competing for Block #" + targetIdx + "...");

        // Mine without adding to chain (other threads may be doing the same)
        Blockchain.Block mined = bc.mineBlockOnly(node.getNodeId());

        if (mined == null) {
            log(node.getNodeId(), "Pending pool emptied while mining — giving up");
            return;
        }

        // Attempt to claim the block — fails if a peer's block arrived first
        boolean won = bc.addMinedBlock(mined);

        if (won) {
            log(node.getNodeId(), "★  WON Block #" + mined.getIndex()
                    + " | nonce=" + String.format("%,d", mined.getNonce())
                    + " | broadcasting...");
            node.broadcastBlock(mined);
            printBalances();
        } else {
            log(node.getNodeId(), "✗  ORPHANED Block #" + mined.getIndex()
                    + " — another miner got there first");
        }
    }

    /** Attempt a double-spend (will always be rejected). */
    static void doSpoof(Node node) {
        String sender   = node.getNodeId();
        double balance  = node.getBlockchain().getBalance(sender);
        double fakeAmt  = balance + 500 + RAND.nextInt(1500);
        String receiver = randomOther(sender);

        log(sender, "SPOOF ATTEMPT: trying to send "
                + fmt(fakeAmt) + " coins to " + receiver
                + " (real balance: " + fmt(balance) + ")");

        boolean ok = node.addTransaction(sender, receiver, fakeAmt);
        if (!ok) {
            log(sender, "SPOOF BLOCKED ✓ — double-spend prevention worked");
        }
    }

    // =========================================================================
    //  Display
    // =========================================================================

    static synchronized void printBalances() {
        // Use node 0 as the reference — all nodes should agree after consensus
        Blockchain bc = nodes[0].getBlockchain();
        Map<String, Double> bal = bc.getAllBalances();

        System.out.println();
        System.out.println("  ┌─ Network State ──────────────────────────────────┐");
        System.out.printf ("  │  Chain length : %-34d│%n", bc.getLength());
        System.out.printf ("  │  Pending txs  : %-34d│%n",
                bc.getPendingTransactions().size());
        System.out.println("  │  Balances:                                        │");
        new TreeMap<>(bal).forEach((addr, b) ->
                System.out.printf("  │    %-10s  %8.2f coins%22s│%n", addr, b, ""));
        System.out.println("  └───────────────────────────────────────────────────┘");
        System.out.println();
    }

    static synchronized void log(String id, String msg) {
        long secs = (System.currentTimeMillis() - start) / 1000;
        System.out.printf("[%02d:%02d] [%-8s] %s%n", secs / 60, secs % 60, id, msg);
    }

    static String randomOther(String exclude) {
        String pick;
        do { pick = IDS[RAND.nextInt(IDS.length)]; } while (pick.equals(exclude));
        return pick;
    }

    static String fmt(double v) { return String.format("%.0f", v); }

    static void printHeader() {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║      BLOCKCHAIN AUTO DEMO — LIVE 4-NODE NETWORK      ║");
        System.out.println("║                                                      ║");
        System.out.println("║  • Nodes autonomously send transactions              ║");
        System.out.println("║  • Nodes compete to mine each block                  ║");
        System.out.println("║  • Spoofs and double-spends are automatically caught ║");
        System.out.println("║  • Press Ctrl+C to stop                              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
