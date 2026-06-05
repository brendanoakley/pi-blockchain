import java.util.*;

// =============================================================================
//  BlockchainDemo.java
//  Interactive command-line interface for running a blockchain node.
//
//  Usage:
//    java BlockchainDemo <nodeId> <port>
//    java BlockchainDemo MacBook 5001
//    java BlockchainDemo Pi1     5002
//    java BlockchainDemo Pi2     5003
//    java BlockchainDemo Pi3     5004
//
//  Commands (type at the prompt):
//    mine        – mine all pending transactions into a new block
//    transaction – create a new transaction
//    status      – print this node's status
//    balance     – look up an account balance
//    blocks      – display the full blockchain
//    validate    – verify chain integrity
//    peers       – view / add peers
//    help        – show command list
//    exit        – shut down and quit
// =============================================================================

public class BlockchainDemo {

    private static Node    node;
    private static Scanner input = new Scanner(System.in);

    public static void main(String[] args) {

        // ------------------------------------------------------------------
        //  Parse arguments
        // ------------------------------------------------------------------
        if (args.length < 2) {
            System.out.println("Usage: java BlockchainDemo <nodeId> <port>");
            System.out.println("  e.g. java BlockchainDemo MacBook 5001");
            System.out.println("       java BlockchainDemo Pi1     5002");
            System.exit(1);
        }

        String nodeId = args[0];
        int    port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("Error: port must be an integer (got '" + args[1] + "')");
            System.exit(1);
            return;
        }

        // ------------------------------------------------------------------
        //  Start node — load saved chain if one exists
        // ------------------------------------------------------------------
        String savePath = nodeId + ".dat";
        Blockchain bc = Blockchain.loadFromFile(savePath);
        bc.setAutoSavePath(savePath);
        node = new Node(nodeId, port, bc);
        node.start();

        // Shut down cleanly on Ctrl-C
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> node.stop()));

        printBanner(nodeId, port);
        runMenu();
    }

    // -------------------------------------------------------------------------
    //  Menu loop
    // -------------------------------------------------------------------------

    private static void runMenu() {
        while (true) {
            System.out.print("\n[" + node.getNodeId() + "] > ");

            if (!input.hasNextLine()) break;   // handle piped / EOF input
            String cmd = input.nextLine().trim().toLowerCase();

            switch (cmd) {
                case "mine":        cmdMine();        break;
                case "transaction": cmdTransaction(); break;
                case "status":      node.printStatus(); break;
                case "balance":     cmdBalance();     break;
                case "blocks":      cmdBlocks();      break;
                case "validate":    cmdValidate();    break;
                case "peers":       cmdPeers();       break;
                case "help":        printHelp();      break;
                case "exit":
                case "quit":
                    System.out.println("Shutting down " + node.getNodeId() + "...");
                    node.stop();
                    System.exit(0);
                    break;
                case "":
                    break;  // blank line — just redisplay prompt
                default:
                    System.out.println("Unknown command: '" + cmd
                            + "'  (type 'help' for a list of commands)");
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Command implementations
    // -------------------------------------------------------------------------

    /** Mine all pending transactions into a new block. */
    private static void cmdMine() {
        System.out.println("\nStarting mining...");
        Blockchain.Block block = node.mine();
        if (block != null) {
            System.out.println("Block #" + block.getIndex() + " mined successfully!");
            System.out.println("Mining reward of " + Blockchain.MINING_REWARD
                    + " coins awarded to " + node.getNodeId());
            System.out.println("New balance of " + node.getNodeId() + ": "
                    + String.format("%.2f", node.getBlockchain().getBalance(node.getNodeId()))
                    + " coins");
        } else {
            System.out.println("No pending transactions — nothing to mine.");
        }
    }

    /** Interactively collect sender / receiver / amount and submit a transaction. */
    private static void cmdTransaction() {
        System.out.println("\n--- New Transaction ---");
        System.out.print("  Sender   : ");
        String sender = input.nextLine().trim();

        System.out.print("  Receiver : ");
        String receiver = input.nextLine().trim();

        System.out.print("  Amount   : ");
        String amountStr = input.nextLine().trim();

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            System.out.println("Invalid amount '" + amountStr + "' — transaction cancelled.");
            return;
        }

        boolean ok = node.addTransaction(sender, receiver, amount);
        if (ok) {
            System.out.println("Transaction queued and broadcast to " + node.getPeers().size() + " peer(s).");
        }
    }

    /** Show the balance of a single account or every account. */
    private static void cmdBalance() {
        System.out.print("\nEnter address (or 'all' for every account): ");
        String addr = input.nextLine().trim();

        if (addr.equalsIgnoreCase("all")) {
            System.out.println("\n--- All Account Balances ---");
            Map<String, Double> balances = node.getBlockchain().getAllBalances();
            if (balances.isEmpty()) {
                System.out.println("  (no accounts found)");
            } else {
                // Sort by address for a consistent display order.
                new TreeMap<>(balances).forEach((a, b) ->
                        System.out.printf("  %-15s  %10.2f coins%n", a, b));
            }
        } else {
            double balance = node.getBlockchain().getBalance(addr);
            System.out.printf("  Balance of %-15s: %.2f coins%n", addr, balance);
        }
    }

    /** Print every block in the chain. */
    private static void cmdBlocks() {
        Blockchain bc = node.getBlockchain();
        System.out.println("\n" + bc);
        System.out.println("Pending transactions: "
                + bc.getPendingTransactions().size());
        for (Blockchain.Transaction tx : bc.getPendingTransactions()) {
            System.out.println("  (pending) " + tx);
        }
    }

    /** Validate chain integrity and report the result. */
    private static void cmdValidate() {
        System.out.println("\nValidating blockchain...");
        boolean valid = node.getBlockchain().isChainValid();
        if (valid) {
            System.out.println("Result: VALID  — chain integrity confirmed.");
        } else {
            System.out.println("Result: INVALID — chain has been tampered with!");
        }
    }

    /** List peers and optionally add a new one. */
    private static void cmdPeers() {
        System.out.println("\n--- Peers ---");
        List<String> peers = node.getPeers();
        if (peers.isEmpty()) {
            System.out.println("  No peers connected.");
        } else {
            for (String p : peers) System.out.println("  " + p);
        }

        System.out.print("\nAdd peer [host:port] or press Enter to skip: ");
        String newPeer = input.nextLine().trim();
        if (!newPeer.isEmpty()) {
            node.addPeer(newPeer);
            System.out.println("Peer added: " + newPeer);
        }
    }

    // -------------------------------------------------------------------------
    //  Display helpers
    // -------------------------------------------------------------------------

    private static void printBanner(String nodeId, int port) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║           PI  BLOCKCHAIN  NODE               ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf ("║  Node ID  :  %-30s ║%n", nodeId);
        System.out.printf ("║  Port     :  %-30d ║%n", port);
        System.out.printf ("║  Reward   :  %-30s ║%n", Blockchain.MINING_REWARD + " coins/block");
        System.out.printf ("║  Difficulty: %-29s ║%n", Blockchain.Block.DIFFICULTY + " leading zeros");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Initial balances (from genesis block):");
        node.getBlockchain().getAllBalances().forEach((addr, bal) ->
                System.out.printf("  %-10s  %.0f coins%n", addr, bal));
        System.out.println();
        System.out.println("Type 'help' for available commands.");
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("=== Commands ===");
        System.out.println("  mine        – mine all pending transactions into a block");
        System.out.println("  transaction – create and broadcast a new transaction");
        System.out.println("  status      – show node info (chain length, peers, etc.)");
        System.out.println("  balance     – check coin balance for an account");
        System.out.println("  blocks      – display the full blockchain");
        System.out.println("  validate    – verify blockchain integrity");
        System.out.println("  peers       – list connected peers / add a new peer");
        System.out.println("  help        – show this help message");
        System.out.println("  exit        – shut down this node and quit");
        System.out.println();
        System.out.println("To connect two nodes running on the same machine:");
        System.out.println("  In terminal 1: java BlockchainDemo MacBook 5001");
        System.out.println("  In terminal 2: java BlockchainDemo Pi1 5002");
        System.out.println("  Then on Pi1 type: peers  →  add  localhost:5001");
        System.out.println("  And on MacBook:   peers  →  add  localhost:5002");
    }
}
