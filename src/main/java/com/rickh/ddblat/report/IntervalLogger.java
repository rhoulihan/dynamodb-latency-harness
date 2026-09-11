package com.rickh.ddblat.report;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Writes HdrHistogram interval logs at a fixed cadence so the report can plot latency over
 * time. Each interval is the delta since the previous sample, which is what the .hlog format
 * expects and what makes a per-interval percentile meaningful.
 *
 * Only the measurement window is logged. Sourcing across the ramp-to-window stats swap would
 * subtract a fuller histogram from an emptier one, which HdrHistogram rejects outright.
 */
public final class IntervalLogger implements AutoCloseable {

    private final HistogramLogWriter writer;
    private final FileOutputStream out;
    private final Supplier<Histogram> source;
    private final long intervalMillis;
    private final long startMillis;

    private Histogram previous;
    private volatile boolean running;
    private Thread thread;

    public IntervalLogger(Path file, Supplier<Histogram> source, Duration interval)
            throws IOException {
        this.out = new FileOutputStream(file.toFile());
        this.writer = new HistogramLogWriter(out);
        this.source = source;
        this.intervalMillis = interval.toMillis();
        this.startMillis = System.currentTimeMillis();
        this.previous = source.get().copy();
        this.previous.reset();
        writer.outputLogFormatVersion();
        writer.outputStartTime(startMillis);
        writer.outputLegend();
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "ddblat-hlog");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            emit();
        }
    }

    private synchronized void emit() {
        Histogram now = source.get().copy();
        Histogram delta = now.copy();
        delta.subtract(previous);
        if (delta.getTotalCount() == 0) {
            previous = now;
            return;
        }
        delta.setStartTimeStamp(startMillis);
        delta.setEndTimeStamp(System.currentTimeMillis());
        writer.outputIntervalHistogram(delta);
        previous = now;
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        emit();          // final partial interval
        out.close();
    }
}
