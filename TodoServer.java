import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A tiny to-do web app using only the JDK (Java 11+).
 *
 * Endpoints:
 *   GET    /api/tasks        -> list all tasks
 *   POST   /api/tasks        -> add a task (request body = task title as plain text)
 *   PATCH  /api/tasks/{id}   -> toggle a task done / not done
 *   DELETE /api/tasks/{id}   -> delete one task
 *   DELETE /api/tasks        -> delete all completed tasks
 *
 * Everything else is served from the ./public folder.
 * Tasks are saved to tasks.tsv so they survive a restart.
 */
public class TodoServer {

    static class Task {
        final int id;
        final String title;
        boolean done;

        Task(int id, String title, boolean done) {
            this.id = id;
            this.title = title;
            this.done = done;
        }
    }

    private static final List<Task> tasks = new ArrayList<>();
    private static int nextId = 1;

    private static final Path DATA_FILE = Paths.get("tasks.tsv");
    private static final Path PUBLIC_DIR = Paths.get("public").toAbsolutePath().normalize();
    private static final int MAX_TITLE_LENGTH = 200;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        loadTasks();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/tasks", TodoServer::handleApi);
        server.createContext("/", TodoServer::handleStatic);
        server.start();

        System.out.println("To-Do app running at http://localhost:" + port);
        System.out.println("Press Ctrl+C to stop.");
    }

    // ---------------------------------------------------------------- API

    private static void handleApi(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if (path.equals("/api/tasks") || path.equals("/api/tasks/")) {
                if (method.equals("GET")) {
                    sendJson(ex, 200, listJson());
                } else if (method.equals("POST")) {
                    String title = readBody(ex).replaceAll("[\\t\\r\\n]+", " ").trim();
                    if (title.isEmpty()) {
                        sendJson(ex, 400, "{\"error\":\"Task cannot be empty.\"}");
                        return;
                    }
                    if (title.length() > MAX_TITLE_LENGTH) {
                        title = title.substring(0, MAX_TITLE_LENGTH);
                    }
                    sendJson(ex, 201, toJson(addTask(title)));
                } else if (method.equals("DELETE")) {
                    clearCompleted();
                    sendJson(ex, 200, listJson());
                } else {
                    sendJson(ex, 405, "{\"error\":\"Method not allowed.\"}");
                }
                return;
            }

            if (path.startsWith("/api/tasks/")) {
                int id;
                try {
                    id = Integer.parseInt(path.substring("/api/tasks/".length()));
                } catch (NumberFormatException e) {
                    sendJson(ex, 400, "{\"error\":\"Invalid task id.\"}");
                    return;
                }

                if (method.equals("PATCH")) {
                    Task t = toggleTask(id);
                    if (t == null) {
                        sendJson(ex, 404, "{\"error\":\"Task not found.\"}");
                    } else {
                        sendJson(ex, 200, toJson(t));
                    }
                } else if (method.equals("DELETE")) {
                    if (removeTask(id)) {
                        sendJson(ex, 200, "{\"removed\":" + id + "}");
                    } else {
                        sendJson(ex, 404, "{\"error\":\"Task not found.\"}");
                    }
                } else {
                    sendJson(ex, 405, "{\"error\":\"Method not allowed.\"}");
                }
                return;
            }

            sendJson(ex, 404, "{\"error\":\"Not found.\"}");
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, "{\"error\":\"Server error.\"}");
        }
    }

    // ------------------------------------------------------ Task storage

    private static synchronized Task addTask(String title) {
        Task t = new Task(nextId++, title, false);
        tasks.add(t);
        saveTasks();
        return t;
    }

    private static synchronized Task toggleTask(int id) {
        for (Task t : tasks) {
            if (t.id == id) {
                t.done = !t.done;
                saveTasks();
                return t;
            }
        }
        return null;
    }

    private static synchronized boolean removeTask(int id) {
        Iterator<Task> it = tasks.iterator();
        while (it.hasNext()) {
            if (it.next().id == id) {
                it.remove();
                saveTasks();
                return true;
            }
        }
        return false;
    }

    private static synchronized void clearCompleted() {
        tasks.removeIf(t -> t.done);
        saveTasks();
    }

    private static synchronized String listJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tasks.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJson(tasks.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String toJson(Task t) {
        return "{\"id\":" + t.id + ",\"title\":\"" + escapeJson(t.title) + "\",\"done\":" + t.done + "}";
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------- Persistence

    private static synchronized void loadTasks() {
        if (!Files.exists(DATA_FILE)) return;
        try {
            for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 3); // id, done, title
                if (parts.length < 3) continue;
                int id = Integer.parseInt(parts[0]);
                tasks.add(new Task(id, parts[2], Boolean.parseBoolean(parts[1])));
                nextId = Math.max(nextId, id + 1);
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("Could not read " + DATA_FILE + ": " + e.getMessage());
        }
    }

    private static synchronized void saveTasks() {
        List<String> lines = new ArrayList<>();
        for (Task t : tasks) {
            lines.add(t.id + "\t" + t.done + "\t" + t.title);
        }
        try {
            Files.write(DATA_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not save " + DATA_FILE + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------ Static files

    private static void handleStatic(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String rel = ex.getRequestURI().getPath();
        if (rel.equals("/")) rel = "/index.html";

        Path file = PUBLIC_DIR.resolve(rel.substring(1)).normalize();

        // Block path traversal such as /../../secret.txt
        if (!file.startsWith(PUBLIC_DIR) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        send(ex, 200, contentType(file.toString()), Files.readAllBytes(file));
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".js"))   return "text/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }

    // ----------------------------------------------------------- Helpers

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (body.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }
}