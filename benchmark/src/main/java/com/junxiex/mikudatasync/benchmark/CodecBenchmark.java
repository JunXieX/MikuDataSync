package com.junxiex.mikudatasync.benchmark;

import com.junxiex.mikudatasync.common.CodecBench;

import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic codec benchmark executed by the benchmark workflow. */
public final class CodecBenchmark {

    private CodecBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        CodecBench.Result result = CodecBench.run(5_000, 50_000);
        String line = CodecBench.describe(result);
        System.out.println(line);

        Path output = Path.of("build", "benchmark-results.txt").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, line + System.lineSeparator());
        System.out.println("results: " + output);
    }
}
