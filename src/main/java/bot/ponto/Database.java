package bot.ponto;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    private final Connection conn;

    public Database(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA busy_timeout = 5000");
            }
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível abrir o banco em " + path, e);
        }
    }

    private void migrate() throws SQLException {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id   INTEGER NOT NULL,
                    user_id    INTEGER NOT NULL,
                    state      TEXT NOT NULL CHECK (state IN ('RUNNING','PAUSED','CLOSED')),
                    started_at INTEGER NOT NULL,
                    ended_at   INTEGER
                )""",
                """
                CREATE TABLE IF NOT EXISTS intervals (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL REFERENCES sessions(id),
                    start_ms   INTEGER NOT NULL,
                    end_ms     INTEGER
                )""",
                "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",

                """
                CREATE TABLE IF NOT EXISTS panels (
                    guild_id   INTEGER NOT NULL,
                    user_id    INTEGER NOT NULL,
                    channel_id INTEGER NOT NULL,
                    message_id INTEGER NOT NULL,
                    PRIMARY KEY (guild_id, user_id)
                )""",

                "CREATE UNIQUE INDEX IF NOT EXISTS ux_sessions_open ON sessions(guild_id, user_id) WHERE state != 'CLOSED'",
                "CREATE INDEX IF NOT EXISTS ix_intervals_session ON intervals(session_id)",
                "CREATE INDEX IF NOT EXISTS ix_intervals_start ON intervals(start_ms)"
        };
        try (Statement st = conn.createStatement()) {
            for (String s : ddl) {
                st.execute(s);
            }
        }
    }

    public synchronized <T> T tx(SqlWork<T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Throwable t) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {

                }
                throw t;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Erro de banco de dados", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {

        }
    }
}
