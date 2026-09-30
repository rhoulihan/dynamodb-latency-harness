package com.rickh.ddblat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The five checked-in MELI configs must load and stay inside the 40,000-unit table quota. */
class MeliConfigsTest {

    @ParameterizedTest
    @ValueSource(ints = {370, 523, 2_000, 10_000, 50_000})
    void loadsAndFitsTheTableQuota(int size) throws Exception {
        Config c = Config.load(Path.of("conf/meli-" + size + ".properties"));
        assertThat(c.itemSize()).isEqualTo(size);
        assertThat(c.batchOps()).isTrue();
        assertThat(c.readRcu()).isLessThanOrEqualTo(40_000);
        assertThat(c.postLoadWcu()).isLessThanOrEqualTo(40_000);
        assertThat(c.loadWcu()).isLessThanOrEqualTo(c.presplitWcu());
    }
}
