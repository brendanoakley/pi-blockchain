import java.io.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// =============================================================================
//  Blockchain.java
//  Core data structures: Transaction, Block, and Blockchain
//  AP CSA Final Project
// =============================================================================

public class Blockchain {

    // =========================================================================
    //  TRANSACTION
    //  Represents a transfer of coins from one address to another.
    // =========================================================================
    public static class Transaction {
        private final String sender;
        private final String receiver;
        private final double amount;
        private final long   timestamp;

        /** Create a new transaction stamped with the current time. */
        public Transaction(String sender, String receiver, double amount) {
            this(sender, receiver, amount, System.currentTimeMillis());
        }

        /** Create a transaction with an explicit timestamp (used when deserialising). */
        public Transaction(String sender, String receiver, double amount, long timestamp) {
            this.sender    = sender;
            this.receiver  = receiver;
            this.amount    = amount;
            this.timestamp = timestamp;
        }

        // --- Getters ----------------------------------------------------------
        public String getSender()    { return sender;    }
        public String getReceiver()  { return receiver;  }
        public double getAmount()    { return amount;    }
        public long   getTimestamp() { return timestamp; }

        /** Compact string used as input to the block hash. */
        public String toData() {
            return sender + receiver + amount + timestamp;
        }

        @Override
        public String toString() {
            return sender + " -> " + receiver + ": " + String.format("%.2f", amount) + " coins";
        }
    }

    // =========================================================================
    //  BLOCK
    //  One link in the chain.  Mining uses SHA-256 proof-of-work.
    // =========================================================================
    public static class Block {

        /** Number of leading zeros required in a valid hash.
         *  Difficulty 4 = ~65 000 hash attempts on average.
         *  Expect ~2-10 seconds depending on hardware. */
        public static final int    DIFFICULTY = 5;
        private static final String TARGET    = "0".repeat(DIFFICULTY);

        private final int                  index;
        private       long                 timestamp;
        private final List<Transaction>    transactions;
        private final String               previousHash;
        private       String               hash;
        private       int                  nonce;
        private final String               minerNodeId;

        /**
         * Construct a new (unmined) block.
         * Call {@link #mineBlock()} afterwards to compute a valid hash.
         */
        public Block(int index, List<Transaction> transactions,
                     String previousHash, String minerNodeId) {
            this.index        = index;
            this.timestamp    = System.currentTimeMillis();
            this.transactions = new ArrayList<>(transactions);
            this.previousHash = previousHash;
            this.minerNodeId  = minerNodeId;
            this.nonce        = 0;
            this.hash         = calculateHash();
        }

        // --- Hashing ----------------------------------------------------------

        /**
         * Compute this block's SHA-256 hash from all its fields.
         * Any change to the block's data produces a completely different hash.
         */
        public String calculateHash() {
            StringBuilder txData = new StringBuilder();
            for (Transaction tx : transactions) {
                txData.append(tx.toData());
            }
            String raw = "" + index + timestamp + txData + previousHash + nonce + minerNodeId;
            return sha256(raw);
        }

        /**
         * Proof-of-Work mining loop.
         * Increments nonce until the hash starts with DIFFICULTY leading zeros.
         */
        public void mineBlock() {
            System.out.println("  [Mining] Block #" + index + " | difficulty=" + DIFFICULTY
                    + " | miner=" + minerNodeId);
            long start = System.currentTimeMillis();

            while (!hash.startsWith(TARGET)) {
                nonce++;
                hash = calculateHash();
            }

            double elapsed = (System.currentTimeMillis() - start) / 1000.0;
            System.out.printf("  [Done]   Block #%d mined in %.2fs | nonce=%d%n",
                    index, elapsed, nonce);
            System.out.println("           Hash: " + hash);
        }

        /** Returns true only when the stored hash is valid AND meets difficulty. */
        public boolean isValid() {
            return hash.equals(calculateHash()) && hash.startsWith(TARGET);
        }

        // --- SHA-256 utility --------------------------------------------------

        /** Compute the hex-encoded SHA-256 digest of the given string. */
        public static String sha256(String data) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest(data.getBytes("UTF-8"));
                StringBuilder hex = new StringBuilder();
                for (byte b : bytes) {
                    String h = Integer.toHexString(0xff & b);
                    if (h.length() == 1) hex.append('0');
                    hex.append(h);
                }
                return hex.toString();
            } catch (Exception e) {
                throw new RuntimeException("SHA-256 failed: " + e.getMessage());
            }
        }

        // --- Getters ----------------------------------------------------------
        public int               getIndex()       { return index;       }
        public long              getTimestamp()   { return timestamp;   }
        public List<Transaction> getTransactions(){ return transactions;}
        public String            getPreviousHash(){ return previousHash;}
        public String            getHash()        { return hash;        }
        public int               getNonce()       { return nonce;       }
        public String            getMinerNodeId() { return minerNodeId; }

        // --- Setters (used only when deserialising a block from the network) --
        public void setHash(String hash)        { this.hash      = hash;      }
        public void setTimestamp(long timestamp){ this.timestamp = timestamp; }
        public void setNonce(int nonce)         { this.nonce     = nonce;     }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("  Block #").append(index).append("\n");
            sb.append("    Timestamp   : ").append(timestamp).append("\n");
            sb.append("    Miner       : ").append(minerNodeId).append("\n");
            sb.append("    Nonce       : ").append(nonce).append("\n");
            sb.append("    Prev Hash   : ").append(previousHash).append("\n");
            sb.append("    Hash        : ").append(hash).append("\n");
            sb.append("    Transactions: ").append(transactions.size()).append("\n");
            for (Transaction tx : transactions) {
                sb.append("      - ").append(tx).append("\n");
            }
            return sb.toString();
        }
    }

    // =========================================================================
    //  BLOCKCHAIN
    //  Maintains the full chain of blocks and the pool of pending transactions.
    //  Enforces balance validation to prevent double-spending.
    // =========================================================================

    /** Coins awarded to the miner of each block. */
    public static final double MINING_REWARD = 50.0;

    private List<Block>       chain;
    private List<Transaction> pendingTransactions;
    private String            autoSavePath = null;

    public Blockchain() {
        chain               = new ArrayList<>();
        pendingTransactions = new ArrayList<>();
        chain.add(createGenesisBlock());
    }

    // --- Genesis block --------------------------------------------------------

    /**
     * The genesis block is block #0 — the hard-coded first block.
     * It seeds the four network nodes with initial coin balances and uses a
     * fixed hash (not mined) so every node starts with identical chain state.
     */
    private Block createGenesisBlock() {
        List<Transaction> genesisTxs = new ArrayList<>();
        genesisTxs.add(new Transaction("SYSTEM", "MacBook", 1000.0, 0));
        genesisTxs.add(new Transaction("SYSTEM", "Pi1",     500.0,  0));
        genesisTxs.add(new Transaction("SYSTEM", "Pi2",     500.0,  0));
        genesisTxs.add(new Transaction("SYSTEM", "Pi3",     500.0,  0));

        Block genesis = new Block(0, genesisTxs, "0", "SYSTEM");
        genesis.setTimestamp(0);
        genesis.setNonce(0);
        // Fixed deterministic hash — every node produces the same genesis hash.
        genesis.setHash(Block.sha256("PI_BLOCKCHAIN_GENESIS_V1"));
        return genesis;
    }

    // --- Transaction management -----------------------------------------------

    /**
     * Validate and queue a transaction.
     * Rejects:
     *   - zero / negative amounts
     *   - transactions where the sender's confirmed balance minus any already-
     *     queued debits would fall below the requested amount (double-spend
     *     prevention)
     *
     * @return true if accepted, false if rejected
     */
    public boolean addTransaction(Transaction tx) {
        if (tx == null) {
            System.out.println("  [TX Rejected] null transaction");
            return false;
        }
        if (tx.getAmount() <= 0) {
            System.out.println("  [TX Rejected] amount must be > 0  (got " + tx.getAmount() + ")");
            return false;
        }

        // SYSTEM is the coin issuer — it may always send.
        if (!tx.getSender().equals("SYSTEM")) {
            double confirmed = getBalance(tx.getSender());

            // Deduct any amounts already queued in the pending pool.
            double pendingDebit = 0.0;
            for (Transaction pending : pendingTransactions) {
                if (pending.getSender().equals(tx.getSender())) {
                    pendingDebit += pending.getAmount();
                }
            }

            double available = confirmed - pendingDebit;
            if (available < tx.getAmount()) {
                System.out.printf("  [TX Rejected] %s has %.2f confirmed, %.2f pending debit — "
                        + "cannot send %.2f%n",
                        tx.getSender(), confirmed, pendingDebit, tx.getAmount());
                return false;
            }
        }

        pendingTransactions.add(tx);
        System.out.println("  [TX Accepted] " + tx);
        return true;
    }

    // --- Mining ---------------------------------------------------------------

    /**
     * Bundle all pending transactions (plus a mining reward) into a new block,
     * mine it with proof-of-work, append it to the chain, and clear the pool.
     *
     * @param minerNodeId the node that gets the mining reward
     * @return the newly mined block, or null if there were no pending transactions
     */
    public Block minePendingTransactions(String minerNodeId) {
        if (pendingTransactions.isEmpty()) {
            System.out.println("  [Mine] No pending transactions — nothing to mine.");
            return null;
        }

        // Append the coinbase / mining-reward transaction.
        pendingTransactions.add(new Transaction("SYSTEM", minerNodeId, MINING_REWARD));

        Block newBlock = new Block(chain.size(), pendingTransactions,
                getLatestBlock().getHash(), minerNodeId);
        newBlock.mineBlock();

        chain.add(newBlock);
        pendingTransactions = new ArrayList<>();
        autoSave();
        return newBlock;
    }

    /**
     * Mine a block WITHOUT adding it to the chain.
     * Used by BlockchainAutoDemo so multiple nodes can race — the winner calls
     * addMinedBlock() to claim it; losers get a previousHash mismatch and orphan.
     */
    public Block mineBlockOnly(String minerNodeId) {
        if (pendingTransactions.isEmpty()) return null;
        List<Transaction> txList = new ArrayList<>(pendingTransactions);
        txList.add(new Transaction("SYSTEM", minerNodeId, MINING_REWARD));
        Block block = new Block(chain.size(), txList,
                getLatestBlock().getHash(), minerNodeId);
        block.mineBlock();
        return block;
    }

    // --- Balance & consensus --------------------------------------------------

    /**
     * Walk every block in the chain and tally the net coin flow for address.
     * SYSTEM debits are intentionally ignored (it is the coin issuer).
     */
    public double getBalance(String address) {
        double balance = 0.0;
        for (Block block : chain) {
            for (Transaction tx : block.getTransactions()) {
                if (tx.getReceiver().equals(address)) balance += tx.getAmount();
                if (tx.getSender().equals(address))   balance -= tx.getAmount();
            }
        }
        return balance;
    }

    /**
     * Return a map of every address → balance derived from the full chain history.
     * SYSTEM is excluded as a sender (it is the coin issuer).
     */
    public Map<String, Double> getAllBalances() {
        Map<String, Double> balances = new HashMap<>();
        for (Block block : chain) {
            for (Transaction tx : block.getTransactions()) {
                balances.merge(tx.getReceiver(), tx.getAmount(), Double::sum);
                if (!tx.getSender().equals("SYSTEM")) {
                    balances.merge(tx.getSender(), -tx.getAmount(), Double::sum);
                }
            }
        }
        return balances;
    }

    /**
     * Validate the entire chain:
     *   - Each block's stored hash must match its recalculated hash.
     *   - Each block's hash must satisfy the difficulty requirement.
     *   - Each block must reference the correct previous block's hash.
     * Genesis (index 0) is trusted and skipped.
     */
    public boolean isChainValid() {
        for (int i = 1; i < chain.size(); i++) {
            Block current  = chain.get(i);
            Block previous = chain.get(i - 1);

            if (!current.getHash().equals(current.calculateHash())) {
                System.out.println("  [Invalid] Block #" + i + " hash mismatch.");
                return false;
            }
            if (!current.getHash().startsWith("0".repeat(Block.DIFFICULTY))) {
                System.out.println("  [Invalid] Block #" + i + " does not meet difficulty.");
                return false;
            }
            if (!current.getPreviousHash().equals(previous.getHash())) {
                System.out.println("  [Invalid] Block #" + i + " broken chain link.");
                return false;
            }
        }
        return true;
    }

    /**
     * Accept a block that was mined by another node.
     * Validates hash integrity, difficulty, and chain linkage before adding.
     * Also removes any matching transactions from the pending pool.
     *
     * @return true if the block was added, false if rejected
     */
    public boolean addMinedBlock(Block block) {
        // Chain linkage check.
        if (!block.getPreviousHash().equals(getLatestBlock().getHash())) {
            System.out.println("  [Block Rejected] previousHash mismatch on block #"
                    + block.getIndex());
            return false;
        }
        // Hash integrity check.
        if (!block.getHash().equals(block.calculateHash())) {
            System.out.println("  [Block Rejected] hash does not match content on block #"
                    + block.getIndex());
            return false;
        }
        // Difficulty check.
        if (!block.getHash().startsWith("0".repeat(Block.DIFFICULTY))) {
            System.out.println("  [Block Rejected] difficulty not met on block #"
                    + block.getIndex());
            return false;
        }

        chain.add(block);

        // Remove transactions now confirmed in this block from the pending pool.
        for (Transaction blockTx : block.getTransactions()) {
            pendingTransactions.removeIf(pending ->
                pending.getSender().equals(blockTx.getSender())
                && pending.getReceiver().equals(blockTx.getReceiver())
                && pending.getAmount() == blockTx.getAmount());
        }
        autoSave();
        return true;
    }

    /**
     * Longest-chain consensus rule.
     * Replace our chain with the supplied chain only if it is longer AND valid.
     *
     * @return true if our chain was replaced
     */
    public boolean replaceChain(List<Block> newChain) {
        if (newChain.size() <= chain.size()) return false;

        // Validate the candidate chain in a temporary object.
        Blockchain temp = new Blockchain();
        temp.chain = new ArrayList<>(newChain);
        if (!temp.isChainValid()) return false;

        chain = new ArrayList<>(newChain);
        return true;
    }

    // --- Accessors ------------------------------------------------------------

    public Block          getLatestBlock()         { return chain.get(chain.size() - 1); }
    public List<Block>    getChain()               { return chain;               }
    public List<Transaction> getPendingTransactions() { return pendingTransactions; }
    public int            getLength()              { return chain.size();        }

    // --- Persistence ----------------------------------------------------------

    /** Set a file path and auto-save to it every time a new block is added. */
    public void setAutoSavePath(String path) { this.autoSavePath = path; }

    private void autoSave() {
        if (autoSavePath != null) saveToFile(autoSavePath);
    }

    /**
     * Write every block (excluding genesis) to a plain-text file.
     * Format per block:
     *   BLOCK:index:timestamp:previousHash:hash:nonce:minerNodeId
     *   TX:sender:receiver:amount:timestamp   (one line per transaction)
     *   END
     */
    public void saveToFile(String path) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("VERSION:1");
            // Genesis (index 0) is always recreated identically — skip it.
            for (int i = 1; i < chain.size(); i++) {
                Block b = chain.get(i);
                pw.println("BLOCK:" + b.getIndex() + ":" + b.getTimestamp() + ":"
                        + b.getPreviousHash() + ":" + b.getHash() + ":"
                        + b.getNonce() + ":" + b.getMinerNodeId());
                for (Transaction tx : b.getTransactions()) {
                    pw.println("TX:" + tx.getSender() + ":" + tx.getReceiver() + ":"
                            + tx.getAmount() + ":" + tx.getTimestamp());
                }
                pw.println("END");
            }
            System.out.println("  [Saved] " + path + " (" + (chain.size() - 1) + " block(s))");
        } catch (IOException e) {
            System.out.println("  [Save Error] " + e.getMessage());
        }
    }

    /**
     * Load a previously saved chain from disk.
     * Returns a fresh Blockchain if the file does not exist or cannot be read.
     * The genesis block is always recreated from scratch — only blocks #1+ are loaded.
     */
    public static Blockchain loadFromFile(String path) {
        Blockchain bc = new Blockchain();
        File file = new File(path);
        if (!file.exists()) return bc;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            List<Transaction> txBuf    = null;
            int    bIndex    = -1;
            long   bTime     = 0;
            String bPrevHash = null, bHash = null, bMiner = null;
            int    bNonce    = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("VERSION:")) continue;

                if (line.startsWith("BLOCK:")) {
                    String[] p = line.split(":", 7);
                    bIndex    = Integer.parseInt(p[1]);
                    bTime     = Long.parseLong(p[2]);
                    bPrevHash = p[3];
                    bHash     = p[4];
                    bNonce    = Integer.parseInt(p[5]);
                    bMiner    = p[6];
                    txBuf     = new ArrayList<>();

                } else if (line.startsWith("TX:") && txBuf != null) {
                    String[] p = line.split(":", 5);
                    txBuf.add(new Transaction(p[1], p[2],
                            Double.parseDouble(p[3]), Long.parseLong(p[4])));

                } else if (line.equals("END") && txBuf != null) {
                    Block block = new Block(bIndex, txBuf, bPrevHash, bMiner);
                    block.setTimestamp(bTime);
                    block.setNonce(bNonce);
                    block.setHash(bHash);
                    bc.chain.add(block);
                    txBuf = null;
                }
            }
            System.out.println("  [Loaded] " + path
                    + " — chain restored to " + bc.chain.size() + " block(s)");
        } catch (Exception e) {
            System.out.println("  [Load Error] " + e.getMessage() + " — starting fresh.");
            return new Blockchain();
        }
        return bc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Blockchain | ").append(chain.size()).append(" block(s) ===\n");
        for (Block block : chain) sb.append(block);
        return sb.toString();
    }
}
