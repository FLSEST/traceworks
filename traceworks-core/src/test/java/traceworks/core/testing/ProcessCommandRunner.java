package traceworks.core.testing;

import traceworks.core.port.CommandRunner;
import traceworks.core.util.ProcessTracking;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * A minimal {@link CommandRunner} for tests that need a real process: it runs the command with {@code bash -c} on
 * the host, ignores image and mounts, and registers a stop handle for the process with
 * {@link ProcessTracking#current()} as a real adapter must, so a cancelled run kills it. Not an adapter: no
 * environment scrubbing, no container, and the handle kills at once instead of asking first.
 */
public final class ProcessCommandRunner implements CommandRunner {

    @Override
    public CommandResult run(CommandRequest request) {
        long start = System.currentTimeMillis();
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-c", request.script());
        if (request.image() == null && request.workingDirectory() != null && !request.workingDirectory().isBlank()) {
            builder.directory(new File(request.workingDirectory()));
        }
        ProcessTracking.Tracker tracker = ProcessTracking.current();
        Process process = null;
        ProcessTracking.StopHandle stop = null;
        try {
            process = builder.start();
            stop = stopHandle(process);
            tracker.started(stop);
            process.getOutputStream().close();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread out = drain(process.getInputStream(), stdout);
            Thread err = drain(process.getErrorStream(), stderr);
            boolean finished = process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                kill(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            out.join(2000);
            err.join(2000);
            return request.delivered(new CommandResult(finished ? process.exitValue() : 137, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8), System.currentTimeMillis() - start, !finished));
        } catch (IOException e) {
            return new CommandResult(1, "", "Error: " + e.getMessage(), System.currentTimeMillis() - start, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, "", "Command interrupted", System.currentTimeMillis() - start, false);
        } finally {
            if (process != null) {
                tracker.finished(stop);
                kill(process);
            }
        }
    }

    private static ProcessTracking.StopHandle stopHandle(Process process) {
        return () -> kill(process);
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static Thread drain(InputStream stream, ByteArrayOutputStream target) {
        return Thread.ofVirtual().start(() -> {
            try (stream) {
                stream.transferTo(target);
            } catch (IOException ignored) {
                // the process was killed
            }
        });
    }
}
