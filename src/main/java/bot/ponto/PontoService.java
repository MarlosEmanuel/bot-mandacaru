package bot.ponto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Regras do registro de ponto. Todas as operações multi-linha rodam em transação. */
public class PontoService {

    public enum State { RUNNING, PAUSED, CLOSED }

    public record Status(State state, long startedAt, long sessionMs, long weekMs) {
    }

    public record Summary(long startedAt, long endedAt, long sessionMs, long weekMs) {
    }

    /**
     * @param consolidatedMs tempo de intervalos já fechados (critério de ordenação)
     * @param runningMs      tempo do intervalo aberto agora (só na semana atual)
     */
    public record RankEntry(long userId, long consolidatedMs, long runningMs) {
        public long totalMs() {
            return consolidatedMs + runningMs;
        }
    }

    private record OpenSession(long id, State state, long startedAt) {
    }

    private final Database db;
    private final Clock clock;
    private final ZoneId zone;

    public PontoService(Database db, Clock clock, ZoneId zone) {
        this.db = db;
        this.clock = clock;
        this.zone = zone;
    }

    // ---------------------------------------------------------------- comandos

    public void iniciar(long guildId, long userId) {
        long now = clock.millis();
        db.tx(c -> {
            Optional<OpenSession> open = findOpen(c, guildId, userId);
            if (open.isPresent()) {
                throw new PontoException("Você já tem um ponto aberto (" + label(open.get().state())
                        + "). Use `/ponto status`.");
            }
            long sid = insertSession(c, guildId, userId, State.RUNNING, now);
            insertInterval(c, sid, now, null);
            return null;
        });
    }

    public void pausar(long guildId, long userId) {
        long now = clock.millis();
        db.tx(c -> {
            OpenSession s = requireOpen(c, guildId, userId);
            if (s.state() != State.RUNNING) {
                throw new PontoException("Seu ponto já está pausado.");
            }
            pause(c, s.id(), now);
            return null;
        });
    }

    public void retomar(long guildId, long userId) {
        long now = clock.millis();
        db.tx(c -> {
            OpenSession s = requireOpen(c, guildId, userId);
            if (s.state() != State.PAUSED) {
                throw new PontoException("Seu ponto já está em andamento.");
            }
            insertInterval(c, s.id(), now, null);
            setState(c, s.id(), State.RUNNING);
            return null;
        });
    }

    public Summary encerrar(long guildId, long userId) {
        return encerrarSeAberto(guildId, userId)
                .orElseThrow(() -> new PontoException("Você não tem ponto aberto. Use `/ponto iniciar`."));
    }

    /** Encerra o ponto (RUNNING ou PAUSED) se houver um aberto; vazio caso contrário. */
    public Optional<Summary> encerrarSeAberto(long guildId, long userId) {
        long now = clock.millis();
        WeekUtil.Window w = WeekUtil.window(now, zone, 0);
        return db.tx(c -> {
            Optional<OpenSession> open = findOpen(c, guildId, userId);
            if (open.isEmpty()) {
                return Optional.<Summary>empty();
            }
            OpenSession s = open.get();
            closeOpenInterval(c, s.id(), now);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sessions SET state='CLOSED', ended_at=? WHERE id=?")) {
                ps.setLong(1, now);
                ps.setLong(2, s.id());
                ps.executeUpdate();
            }
            return Optional.of(new Summary(s.startedAt(), now, sessionTotal(c, s.id(), now),
                    userTotal(c, guildId, userId, w, now)));
        });
    }

    public Optional<Status> status(long guildId, long userId) {
        long now = clock.millis();
        WeekUtil.Window w = WeekUtil.window(now, zone, 0);
        return db.tx(c -> findOpen(c, guildId, userId).map(s -> {
            try {
                return new Status(s.state(), s.startedAt(), sessionTotal(c, s.id(), now), userTotal(c, guildId, userId, w, now));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    // ----------------------------------------------------------------- ranking

    public WeekUtil.Window week(int weeksAgo) {
        return WeekUtil.window(clock.millis(), zone, weeksAgo);
    }

    /** Ranking completo da semana (0 = atual, 1 = anterior, ...), ordenado por tempo consolidado. */
    public List<RankEntry> ranking(long guildId, int weeksAgo) {
        long now = clock.millis();
        WeekUtil.Window w = WeekUtil.window(now, zone, weeksAgo);
        return db.tx(c -> totals(c, guildId, null, w, now, Integer.MAX_VALUE));
    }

    private long userTotal(Connection c, long guildId, long userId, WeekUtil.Window w, long now)
            throws SQLException {
        List<RankEntry> r = totals(c, guildId, userId, w, now, 1);
        return r.isEmpty() ? 0 : r.get(0).totalMs();
    }

    private static List<RankEntry> totals(Connection c, long guildId, Long userId, WeekUtil.Window w,
                                          long now, int limit) throws SQLException {
        // intervalo aberto só é "em andamento" se a janela ainda não terminou (semana atual)
        int current = now < w.endMs() ? 1 : 0;
        String sql = "SELECT s.user_id, "
                + "SUM(MIN(COALESCE(i.end_ms, ?), ?) - MAX(i.start_ms, ?)) AS total_ms, "
                + "SUM(CASE WHEN i.end_ms IS NULL AND ? = 1 THEN MIN(?, ?) - MAX(i.start_ms, ?) ELSE 0 END) AS running_ms "
                + "FROM intervals i JOIN sessions s ON s.id = i.session_id "
                + "WHERE s.guild_id = ? "
                + (userId != null ? "AND s.user_id = ? " : "")
                + "AND i.start_ms < ? AND COALESCE(i.end_ms, ?) > ? "
                + "GROUP BY s.user_id ORDER BY (total_ms - running_ms) DESC, s.user_id ASC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int n = 1;
            ps.setLong(n++, now);
            ps.setLong(n++, w.endMs());
            ps.setLong(n++, w.startMs());
            ps.setInt(n++, current);
            ps.setLong(n++, now);
            ps.setLong(n++, w.endMs());
            ps.setLong(n++, w.startMs());
            ps.setLong(n++, guildId);
            if (userId != null) {
                ps.setLong(n++, userId);
            }
            ps.setLong(n++, w.endMs());
            ps.setLong(n++, now);
            ps.setLong(n++, w.startMs());
            ps.setInt(n, limit);
            List<RankEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long total = rs.getLong(2);
                    long running = rs.getLong(3);
                    out.add(new RankEntry(rs.getLong(1), total - running, running));
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- rollover

    /**
     * Virada de semana. Para cada sessão não encerrada iniciada antes de {@code limitMs}:
     * fecha a sessão (e os intervalos que atravessam o limite) em limitMs e abre uma nova
     * sessão no mesmo estado. Idempotente: sessões novas começam em limitMs e não são tocadas.
     *
     * @return quantas sessões foram viradas
     */
    public int rollover(long limitMs) {
        return db.tx(c -> {
            record Row(long id, long guild, long user, String state) {
            }
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, guild_id, user_id, state FROM sessions WHERE state != 'CLOSED' AND started_at < ?")) {
                ps.setLong(1, limitMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)));
                    }
                }
            }
            for (Row r : rows) {
                // fecha primeiro: o índice único parcial só admite uma sessão aberta por pessoa
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE sessions SET state='CLOSED', ended_at=? WHERE id=?")) {
                    ps.setLong(1, limitMs);
                    ps.setLong(2, r.id());
                    ps.executeUpdate();
                }
                long newId = insertSession(c, r.guild(), r.user(), State.valueOf(r.state()), limitMs);

                // intervalos que atravessam o limite (abertos ou fechados depois dele): divide em dois
                List<long[]> straddling = new ArrayList<>(); // {id, end_ms ou -1}
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, end_ms FROM intervals WHERE session_id=? AND start_ms < ? "
                                + "AND (end_ms IS NULL OR end_ms > ?)")) {
                    ps.setLong(1, r.id());
                    ps.setLong(2, limitMs);
                    ps.setLong(3, limitMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long id = rs.getLong(1);
                            long end = rs.getLong(2);
                            boolean open = rs.wasNull(); // logo após ler end_ms
                            straddling.add(new long[]{id, open ? -1 : end});
                        }
                    }
                }
                for (long[] i : straddling) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE intervals SET end_ms=? WHERE id=?")) {
                        ps.setLong(1, limitMs);
                        ps.setLong(2, i[0]);
                        ps.executeUpdate();
                    }
                    insertInterval(c, newId, limitMs, i[1] < 0 ? null : i[1]);
                }
                // intervalos que já começaram depois do limite (corrida de milissegundos) vão para a nova sessão
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE intervals SET session_id=? WHERE session_id=? AND start_ms >= ?")) {
                    ps.setLong(1, newId);
                    ps.setLong(2, r.id());
                    ps.setLong(3, limitMs);
                    ps.executeUpdate();
                }
            }
            return rows.size();
        });
    }

    // ------------------------------------------------------------------ painéis

    /** Onde está a mensagem do painel de uma pessoa. */
    public record PanelRef(long channelId, long messageId) {
    }

    public void savePanel(long guildId, long userId, long channelId, long messageId) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO panels(guild_id, user_id, channel_id, message_id) VALUES(?,?,?,?) "
                            + "ON CONFLICT(guild_id, user_id) DO UPDATE SET "
                            + "channel_id = excluded.channel_id, message_id = excluded.message_id")) {
                ps.setLong(1, guildId);
                ps.setLong(2, userId);
                ps.setLong(3, channelId);
                ps.setLong(4, messageId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<PanelRef> findPanel(long guildId, long userId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT channel_id, message_id FROM panels WHERE guild_id=? AND user_id=?")) {
                ps.setLong(1, guildId);
                ps.setLong(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new PanelRef(rs.getLong(1), rs.getLong(2))) : Optional.<PanelRef>empty();
                }
            }
        });
    }

    public void clearPanel(long guildId, long userId) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM panels WHERE guild_id=? AND user_id=?")) {
                ps.setLong(1, guildId);
                ps.setLong(2, userId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // --------------------------------------------------------------- heartbeat

    public void heartbeat() {
        long now = clock.millis();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO meta(key, value) VALUES('heartbeat', ?) "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, Long.toString(now));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Chamar uma vez na inicialização: fecha intervalos abertos no último heartbeat (o tempo
     * offline não conta) e pausa essas sessões.
     *
     * @return quantas sessões foram pausadas
     */
    public int recoverFromHeartbeat() {
        long now = clock.millis();
        return db.tx(c -> {
            Long hb = null;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key='heartbeat'")) {
                if (rs.next()) {
                    hb = Long.parseLong(rs.getString(1));
                }
            }
            List<long[]> open = new ArrayList<>(); // {interval id, session id, start}
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, session_id, start_ms FROM intervals WHERE end_ms IS NULL")) {
                while (rs.next()) {
                    open.add(new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
                }
            }
            for (long[] i : open) {
                long end = hb == null ? i[2] : Math.max(i[2], Math.min(hb, now));
                try (PreparedStatement ps = c.prepareStatement("UPDATE intervals SET end_ms=? WHERE id=?")) {
                    ps.setLong(1, end);
                    ps.setLong(2, i[0]);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE sessions SET state='PAUSED' WHERE id=? AND state='RUNNING'")) {
                    ps.setLong(1, i[1]);
                    ps.executeUpdate();
                }
            }
            return open.size();
        });
    }

    // ----------------------------------------------------------------- helpers

    private static String label(State s) {
        return s == State.RUNNING ? "em andamento" : "pausado";
    }

    private static Optional<OpenSession> findOpen(Connection c, long guildId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, state, started_at FROM sessions WHERE guild_id=? AND user_id=? AND state != 'CLOSED'")) {
            ps.setLong(1, guildId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new OpenSession(rs.getLong(1), State.valueOf(rs.getString(2)), rs.getLong(3)))
                        : Optional.empty();
            }
        }
    }

    private static OpenSession requireOpen(Connection c, long guildId, long userId) throws SQLException {
        return findOpen(c, guildId, userId)
                .orElseThrow(() -> new PontoException("Você não tem ponto aberto. Use `/ponto iniciar`."));
    }

    private static long insertSession(Connection c, long guildId, long userId, State state, long startedAt)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sessions(guild_id, user_id, state, started_at) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, guildId);
            ps.setLong(2, userId);
            ps.setString(3, state.name());
            ps.setLong(4, startedAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertInterval(Connection c, long sessionId, long startMs, Long endMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO intervals(session_id, start_ms, end_ms) VALUES(?,?,?)")) {
            ps.setLong(1, sessionId);
            ps.setLong(2, startMs);
            if (endMs == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, endMs);
            }
            ps.executeUpdate();
        }
    }

    private static void closeOpenInterval(Connection c, long sessionId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE intervals SET end_ms=? WHERE session_id=? AND end_ms IS NULL")) {
            ps.setLong(1, now);
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        }
    }

    private static void setState(Connection c, long sessionId, State state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE sessions SET state=? WHERE id=?")) {
            ps.setString(1, state.name());
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        }
    }

    private static void pause(Connection c, long sessionId, long now) throws SQLException {
        closeOpenInterval(c, sessionId, now);
        setState(c, sessionId, State.PAUSED);
    }

    private static long sessionTotal(Connection c, long sessionId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(COALESCE(end_ms, ?) - start_ms), 0) FROM intervals WHERE session_id=?")) {
            ps.setLong(1, now);
            ps.setLong(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
