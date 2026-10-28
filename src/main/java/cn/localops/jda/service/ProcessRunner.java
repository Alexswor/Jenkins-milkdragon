package cn.localops.jda.service;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class ProcessRunner {
    public ProcessResult run(List<String> command, Duration timeout) throws IOException {
        return run(command, timeout, 4_000);
    }

    public ProcessResult run(List<String> command, Duration timeout, int maximumOutputCharacters) throws IOException {
        if (maximumOutputCharacters < 1 || maximumOutputCharacters > 1_048_576) { throw new IllegalArgumentException("命令输出上限无效。"); }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        long deadline = System.nanoTime() + timeout.toNanos();
        FutureTask<String> captured = new FutureTask<>(() -> {
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            int byteLimit = maximumOutputCharacters * 4 + 16;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                int remaining = byteLimit - output.size();
                if (remaining > 0) { output.write(buffer, 0, Math.min(count, remaining)); }
                // Continue draining after the limit so verbose commands cannot block on a full pipe.
            }
            return output.toString(StandardCharsets.UTF_8);
        });
        Thread reader = Thread.startVirtualThread(captured);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { throw new TimeoutException(); }
            String output = captured.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return new ProcessResult(process.exitValue(), sanitize(output, maximumOutputCharacters));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("命令执行已取消。", exception);
        } catch (TimeoutException exception) {
            throw new IOException("命令执行超时。");
        } catch (ExecutionException exception) {
            throw new IOException("命令输出读取失败。", exception.getCause());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
            process.getInputStream().close();
            reader.interrupt();
        }
    }

    private static String sanitize(String output, int maximumCharacters) {
        if (output.length() <= maximumCharacters) {
            return output.strip();
        }
        return output.substring(0, maximumCharacters).strip() + "\n……输出已截断";
    }

    public record ProcessResult(int exitCode, String output) {
        public boolean successful() {
            return exitCode == 0;
        }
    }
}

