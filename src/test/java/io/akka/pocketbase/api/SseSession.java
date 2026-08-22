package io.akka.pocketbase.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One open realtime stream, read the way a browser reads it — a frame at a time, with the
 * connection left open.
 *
 * <p>The TestKit's own HTTP client reads a whole response before returning it, which a stream that
 * never ends does not give it. Every session rule is about what a second call sees while the first
 * is still open, so the stream has to be read incrementally or none of them is reachable.
 */
final class SseSession implements AutoCloseable {

  record Frame(String id, String event, String data, List<String> rawLines) {}

  private final HttpClient client = HttpClient.newHttpClient();
  private final List<Frame> frames = new CopyOnWriteArrayList<>();
  private final Thread reader;
  private volatile HttpResponse<java.io.InputStream> response;
  private volatile boolean stopped;

  SseSession(int port, String forwardedFor) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/realtime"))
            .timeout(Duration.ofSeconds(30))
            .GET();
    if (forwardedFor != null) {
      builder.header("X-Forwarded-For", forwardedFor);
    }
    var request = builder.build();

    reader =
        new Thread(
            () -> {
              try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var in =
                    new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                  var current = new ArrayList<String>();
                  String line;
                  while (!stopped && (line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                      if (!current.isEmpty()) {
                        frames.add(parse(current));
                        current = new ArrayList<>();
                      }
                    } else {
                      current.add(line);
                    }
                  }
                }
              } catch (Exception ignored) {
                // the stream ends when the test closes it, and that is not a failure
              }
            });
    reader.setDaemon(true);
    reader.start();
  }

  private static Frame parse(List<String> lines) {
    String id = null;
    String event = null;
    var data = new StringBuilder();
    for (var line : lines) {
      if (line.startsWith("id:")) {
        id = line.substring(3).trim();
      } else if (line.startsWith("event:")) {
        event = line.substring(6).trim();
      } else if (line.startsWith("data:")) {
        data.append(line.substring(5).trim());
      }
    }
    return new Frame(id, event, data.toString(), List.copyOf(lines));
  }

  /** Blocks until at least {@code n} frames have arrived, or the wait runs out. */
  List<Frame> awaitFrames(int n, Duration timeout) {
    var deadline = System.nanoTime() + timeout.toNanos();
    while (frames.size() < n && System.nanoTime() < deadline) {
      sleep(20);
    }
    return List.copyOf(frames);
  }

  /** Waits out a window in which a frame could have arrived, for an assertion that none did. */
  List<Frame> framesAfterSettling() {
    sleep(1200);
    return List.copyOf(frames);
  }

  String clientId() {
    var first = awaitFrames(1, Duration.ofSeconds(15));
    if (first.isEmpty()) {
      throw new IllegalStateException("no frame arrived on the stream");
    }
    var data = first.get(0).data();
    int start = data.indexOf("\"clientId\":\"") + "\"clientId\":\"".length();
    return data.substring(start, data.indexOf('"', start));
  }

  @Override
  public void close() {
    stopped = true;
    var current = response;
    if (current != null) {
      try {
        current.body().close();
      } catch (Exception ignored) {
        // already gone
      }
    }
    reader.interrupt();
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
