package com.rickh.ddblat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ConfigBatchOpsTest {

    @TempDir Path tmp;

    private static final String BASE = """
        table=t
        region=us-east-1
        itemCount=4096
        loadWcu=1000
        readRcu=1000
        resultsDir=/tmp/r
        """;

    private Config load(String extra) throws Exception {
        Path p = tmp.resolve("c.properties");
        Files.writeString(p, BASE + extra);
        return Config.load(p);
    }

    @Test
    void defaultsLeaveExistingConfigsUnchanged() throws Exception {
        Config c = load("");
        assertThat(c.itemSize()).isEqualTo(60_416);
        assertThat(c.batchOps()).isFalse();
        assertThat(c.postLoadWcu()).isEqualTo(10);
    }

    @Test
    void batchOpsReadsSizesAndHoldsWriteCapacityFromSwitch() throws Exception {
        Config c = load("itemSize=523\nbatchOps=true\nbatchWriteWcu=5000\ntxnWcu=20000\ntxnItems=100\n");
        assertThat(c.itemSize()).isEqualTo(523);
        assertThat(c.batchGetSize()).isEqualTo(100);
        assertThat(c.batchWriteSize()).isEqualTo(25);
        assertThat(c.postLoadWcu()).isEqualTo(20_000);
    }

    @Test
    void rejectsLimitsDynamoDbWouldRejectPerCall() {
        assertThatThrownBy(() -> load("batchWriteSize=26\n")).hasMessageContaining("batchWriteSize");
        assertThatThrownBy(() -> load("batchGetSize=101\n")).hasMessageContaining("batchGetSize");
        assertThatThrownBy(() -> load("txnItems=101\n")).hasMessageContaining("txnItems");
        assertThatThrownBy(() -> load("itemSize=100\n")).hasMessageContaining("itemSize");
        assertThatThrownBy(() -> load("batchOps=true\n")).hasMessageContaining("txnWcu");
    }
}
