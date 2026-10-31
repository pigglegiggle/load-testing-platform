package dev.apirunner;

import static dev.apirunner.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class Store {
    private final JdbcTemplate database;
    private final ObjectMapper json;

    public Store(JdbcTemplate database, ObjectMapper json) {
        this.database = database;
        this.json = json;
        database.execute(
                "CREATE TABLE IF NOT EXISTS scenarios (id VARCHAR(36) PRIMARY KEY, name"
                    + " VARCHAR(120) NOT NULL, definition TEXT NOT NULL, updated_at VARCHAR(40) NOT"
                    + " NULL)");
        database.execute(
                "CREATE TABLE IF NOT EXISTS runs (id VARCHAR(36) PRIMARY KEY, state VARCHAR(24) NOT"
                        + " NULL, started_at VARCHAR(40) NOT NULL, snapshot TEXT NOT NULL)");
        database.execute("CREATE INDEX IF NOT EXISTS runs_started ON runs(started_at)");
        // A new process cannot resume in-flight HTTP operations safely.
        for (String payload :
                database.queryForList(
                        "SELECT snapshot FROM runs WHERE state IN ('RUNNING','CANCELLING')",
                        String.class)) {
            try {
                ObjectNode recovered = (ObjectNode) json.readTree(payload);
                recovered
                        .put("state", "INTERRUPTED")
                        .put("finishedAt", Instant.now().toString())
                        .put("activeRequests", 0)
                        .put("message", "Backend restarted; in-flight work was not replayed.");
                database.update(
                        "UPDATE runs SET state='INTERRUPTED', snapshot=? WHERE id=?",
                        recovered.toString(),
                        recovered.get("id").asText());
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot recover stored run", exception);
            }
        }
    }

    public List<SavedScenario> scenarios() {
        return database.query(
                "SELECT id, definition, updated_at FROM scenarios ORDER BY updated_at DESC",
                (row, ignored) ->
                        new SavedScenario(
                                row.getString(1),
                                decode(row.getString(2), Scenario.class),
                                row.getString(3)));
    }

    public synchronized SavedScenario saveScenario(String id, Scenario definition) {
        if (id == null) {
            Definitions.require(
                    database.queryForObject("SELECT COUNT(*) FROM scenarios", Integer.class) < 100,
                    "At most 100 saved scenarios");
            id = UUID.randomUUID().toString();
        } else {
            uuid(id);
            if (database.queryForObject(
                            "SELECT COUNT(*) FROM scenarios WHERE id=?", Integer.class, id)
                    == 0) throw new Missing("Scenario not found");
        }
        String updated = sortTime(Instant.now().toString());
        int changed =
                database.update(
                        "UPDATE scenarios SET name=?, definition=?, updated_at=? WHERE id=?",
                        definition.name(),
                        encode(definition),
                        updated,
                        id);
        if (changed == 0)
            database.update(
                    "INSERT INTO scenarios (id,name,definition,updated_at) VALUES (?,?,?,?)",
                    id,
                    definition.name(),
                    encode(definition),
                    updated);
        return new SavedScenario(id, definition, updated);
    }

    public void deleteScenario(String id) {
        uuid(id);
        if (database.update("DELETE FROM scenarios WHERE id=?", id) == 0)
            throw new Missing("Scenario not found");
    }

    public void insertRun(Snapshot snapshot) {
        database.update(
                "INSERT INTO runs (id,state,started_at,snapshot) VALUES (?,?,?,?)",
                snapshot.id(),
                snapshot.state(),
                sortTime(snapshot.startedAt()),
                encode(snapshot));
    }

    public void checkpoint(Snapshot snapshot) {
        database.update(
                "UPDATE runs SET state=?, snapshot=? WHERE id=?",
                snapshot.state(),
                encode(snapshot),
                snapshot.id());
    }

    public Snapshot run(String id) {
        uuid(id);
        List<String> rows =
                database.queryForList("SELECT snapshot FROM runs WHERE id=?", String.class, id);
        if (rows.isEmpty()) throw new Missing("Run not found");
        return decode(rows.getFirst(), Snapshot.class);
    }

    public List<History> history() {
        return database.query(
                "SELECT snapshot FROM runs ORDER BY started_at DESC LIMIT 100",
                (row, ignored) -> {
                    Snapshot s = decode(row.getString(1), Snapshot.class);
                    return new History(
                            s.id(),
                            s.name(),
                            s.state(),
                            s.startedAt(),
                            s.attempted(),
                            s.failed(),
                            s.p95Ms(),
                            s.requestsPerSecond());
                });
    }

    @Transactional
    public void prune() {
        List<String> ids =
                database.queryForList(
                        "SELECT id FROM runs WHERE state NOT IN ('RUNNING','CANCELLING') ORDER BY"
                                + " started_at DESC",
                        String.class);
        for (int i = 200; i < ids.size(); i++)
            database.update("DELETE FROM runs WHERE id=?", ids.get(i));
    }

    private String sortTime(String value) {
        return new java.time.format.DateTimeFormatterBuilder()
                .appendInstant(9)
                .toFormatter()
                .format(Instant.parse(value));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize stored data", exception);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read stored data", exception);
        }
    }

    private void uuid(String id) {
        try {
            UUID.fromString(id);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid identifier");
        }
    }

    static class Missing extends RuntimeException {
        Missing(String message) {
            super(message);
        }
    }
}
