package com.rickh.ddblat.phase;

import com.rickh.ddblat.worker.IndexSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resumable, shardable index source for the load phase.
 *
 * Resume uses a conservative watermark rather than a set of completed chunks. The
 * checkpoint records acknowledged progress minus a safety margin, so a crash may cause
 * a small amount of work to be redone but can never cause work to be skipped. PutItem
 * is idempotent, so redoing costs only capacity, while skipping would leave holes in
 * the dataset and break the item-count validity criterion.
 *
 * Sharding is by modulo: client i owns every index where index % clientCount == i.
 *
 * The checkpoint file lives outside {@code resultsDir} on the instance's own disk (see
 * {@code Config.checkpointFile}'s javadoc) precisely so it survives a wipe between runs --
 * but the DynamoDB table it was written against does NOT survive between runs: a fresh smoke
 * run deletes and recreates the table. A watermark from a previous table is not "a small
 * amount of redone work," it is a license to silently write only the LAST few thousand
 * indices of a brand new, otherwise-empty table and call the load complete -- exactly the
 * task-17 field incident, where a stale watermark of 15,360 against a fresh 16,384-item table
 * loaded only the final 1,024 items and the read phases then measured latency against a
 * ~93%-empty table. {@link TableIdentity} ties the checkpoint to the specific table instance
 * (name + CreationDateTime, which changes every time the table is deleted and recreated) and
 * to the configured item count, so any mismatch is treated exactly like an unreadable file:
 * discarded, logged loudly, and resumed from zero.
 */
public final class LoadPhase {

    /** Acknowledged progress is trailed by this many requests before being persisted. */
    private static final long SAFETY_MARGIN = 1024L;

    private LoadPhase() {}

    /**
     * Identifies the specific table instance a checkpoint was written against. {@code
     * createdEpochMillis} is the table's {@code CreationDateTime} from DescribeTable: it
     * changes every time the table is deleted and recreated, even under the identical name,
     * which is exactly the case a table name alone cannot detect.
     */
    public record TableIdentity(String tableName, long createdEpochMillis) {}

    public static Source source(TableIdentity table, int itemCount, Path checkpoint,
                                int clientIndex, int clientCount) {
        return new Source(table, itemCount, checkpoint, clientIndex, clientCount);
    }

    public static final class Source implements IndexSource {

        private final TableIdentity table;
        private final int itemCount;
        private final Path checkpoint;
        private final int clientIndex;
        private final int clientCount;
        private final long resumeFrom;
        private final AtomicLong cursor;

        private Source(TableIdentity table, int itemCount, Path checkpoint,
                       int clientIndex, int clientCount) {
            if (clientCount < 1 || clientIndex < 0 || clientIndex >= clientCount) {
                throw new IllegalArgumentException(
                    "bad shard: index=" + clientIndex + " count=" + clientCount);
            }
            this.table = table;
            this.itemCount = itemCount;
            this.checkpoint = checkpoint;
            this.clientIndex = clientIndex;
            this.clientCount = clientCount;
            this.resumeFrom = readWatermark(table, itemCount, checkpoint);
            this.cursor = new AtomicLong(this.resumeFrom);
        }

        /** Position in this client's own sequence that a resume would restart from. */
        public long resumedFromIndex() {
            return resumeFrom;
        }

        public long totalForThisClient() {
            long full = itemCount / clientCount;
            long remainder = itemCount % clientCount;
            return full + (clientIndex < remainder ? 1 : 0);
        }

        @Override
        public int next() {
            long n = cursor.getAndIncrement();
            long index = n * clientCount + clientIndex;
            if (index >= itemCount) return -1;
            return (int) index;
        }

        /**
         * Persists acknowledged progress, trailed by the safety margin, alongside the table
         * identity and item count this progress is only valid against. DynamoDB table names
         * are restricted to letters, digits, underscore, hyphen and period, so '|' is a safe,
         * unambiguous delimiter -- no escaping needed.
         */
        public void checkpoint(long completedCount) throws IOException {
            long watermark = Math.max(0L, completedCount - SAFETY_MARGIN);
            String line = table.tableName() + '|' + table.createdEpochMillis() + '|'
                + itemCount + '|' + watermark;
            Path tmp = checkpoint.resolveSibling(checkpoint.getFileName() + ".tmp");
            Files.writeString(tmp, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, checkpoint,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }

        /**
         * Returns the persisted watermark only if the checkpoint was written for THIS table
         * instance and item count; otherwise logs loudly (a checkpoint that is present but
         * cannot be trusted is never silent -- silence is exactly how task-17 happened) and
         * returns 0 so the load restarts from scratch rather than risk skipping work against
         * what is, from the table's point of view, an entirely different dataset. A simply
         * ABSENT file is the ordinary first-run case and needs no such warning.
         */
        private static long readWatermark(TableIdentity table, int itemCount, Path checkpoint) {
            if (!Files.exists(checkpoint)) return 0L;
            String raw;
            try {
                raw = Files.readString(checkpoint, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                System.err.println("CHECKPOINT-INVALID: " + checkpoint + " could not be read ("
                    + e.getMessage() + "); starting LOAD from index 0 rather than risk silently "
                    + "skipping work");
                return 0L;
            }
            if (raw.isEmpty()) return 0L;

            // Pre-task-17 checkpoints are a bare integer with no identity fields at all: a
            // 1-field split. Never trusted, since a bare watermark carries none of the
            // information needed to tell whether it still applies to this table -- treated
            // exactly like a corrupt file, not silently honored.
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4) {
                System.err.println("CHECKPOINT-INVALID: " + checkpoint + " is not in the "
                    + "expected tableName|createdEpochMillis|itemCount|watermark format "
                    + "(found " + parts.length + " field(s)); starting LOAD from index 0 rather "
                    + "than risk silently skipping work");
                return 0L;
            }
            String storedTable = parts[0];
            long storedCreated, storedWatermark;
            int storedItemCount;
            try {
                storedCreated = Long.parseLong(parts[1]);
                storedItemCount = Integer.parseInt(parts[2]);
                storedWatermark = Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                System.err.println("CHECKPOINT-INVALID: " + checkpoint + " has a malformed "
                    + "numeric field (" + e.getMessage() + "); starting LOAD from index 0 "
                    + "rather than risk silently skipping work");
                return 0L;
            }

            if (!storedTable.equals(table.tableName())
                    || storedCreated != table.createdEpochMillis()
                    || storedItemCount != itemCount) {
                System.err.println("CHECKPOINT-INVALID: " + checkpoint + " was written for "
                    + "table=" + storedTable + " created=" + storedCreated
                    + " itemCount=" + storedItemCount + ", but the live target is table="
                    + table.tableName() + " created=" + table.createdEpochMillis()
                    + " itemCount=" + itemCount + " -- the table was almost certainly deleted "
                    + "and recreated since this checkpoint was written (task-17: this is what "
                    + "silently loaded 7% of a dataset and then measured read latency against "
                    + "missing data); starting LOAD from index 0 rather than resuming against "
                    + "what is effectively a different table");
                return 0L;
            }
            return storedWatermark;
        }
    }
}
