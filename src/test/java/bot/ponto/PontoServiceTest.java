package bot.ponto;

import bot.ponto.PontoService.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PontoServiceTest {

    static final ZoneId ZONE = ZoneId.of("America/Fortaleza");
    static final long GUILD = 1L;
    static final long ALICE = 10L;
    static final long BOB = 20L;

    Database db;
    MutableClock clock;
    PontoService service;

    static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant();
    }

    @BeforeEach
    void setUp() {
        db = new Database(":memory:");
        clock = new MutableClock(at("2026-11-10T12:00:00"));
        service = new PontoService(db, clock, ZONE);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    static Duration min(long m) {
        return Duration.ofMinutes(m);
    }

    long sessionCount() {
        return db.tx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM sessions")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void pausaERetomadaSomamCorretamente() {
        service.iniciar(GUILD, ALICE);
        clock.advance(min(10));
        service.pausar(GUILD, ALICE);
        clock.advance(min(5));
        assertEquals(min(10).toMillis(), service.status(GUILD, ALICE).orElseThrow().sessionMs());
        service.retomar(GUILD, ALICE);
        clock.advance(min(20));

        var st = service.status(GUILD, ALICE).orElseThrow();
        assertEquals(State.RUNNING, st.state());
        assertEquals(min(30).toMillis(), st.sessionMs());
    }

    @Test
    void encerrarInformaTotaisDaSessaoEDaSemana() {
        service.iniciar(GUILD, ALICE);
        clock.advance(min(30));
        var first = service.encerrar(GUILD, ALICE);
        assertEquals(min(30).toMillis(), first.sessionMs());
        assertEquals(min(30).toMillis(), first.weekMs());
        assertTrue(service.status(GUILD, ALICE).isEmpty());

        clock.advance(min(60));
        service.iniciar(GUILD, ALICE);
        clock.advance(min(15));
        var second = service.encerrar(GUILD, ALICE);
        assertEquals(min(15).toMillis(), second.sessionMs());
        assertEquals(min(45).toMillis(), second.weekMs());
    }

    @Test
    void soUmaSessaoAbertaPorPessoa() {
        service.iniciar(GUILD, ALICE);
        assertThrows(PontoException.class, () -> service.iniciar(GUILD, ALICE));
        service.iniciar(GUILD, BOB);
    }

    @Test
    void transicoesInvalidas() {
        assertThrows(PontoException.class, () -> service.pausar(GUILD, ALICE));
        service.iniciar(GUILD, ALICE);
        assertThrows(PontoException.class, () -> service.retomar(GUILD, ALICE));
        service.pausar(GUILD, ALICE);
        assertThrows(PontoException.class, () -> service.pausar(GUILD, ALICE));
    }

    @Test
    void encerrarSeAbertoEncerraRunningEPaused() {
        assertTrue(service.encerrarSeAberto(GUILD, ALICE).isEmpty());

        service.iniciar(GUILD, ALICE);
        clock.advance(min(10));
        var r = service.encerrarSeAberto(GUILD, ALICE).orElseThrow();
        assertEquals(min(10).toMillis(), r.sessionMs());
        assertTrue(service.status(GUILD, ALICE).isEmpty());

        service.iniciar(GUILD, BOB);
        clock.advance(min(7));
        service.pausar(GUILD, BOB);
        clock.advance(min(30));
        var p = service.encerrarSeAberto(GUILD, BOB).orElseThrow();
        assertEquals(min(7).toMillis(), p.sessionMs());
        assertTrue(service.status(GUILD, BOB).isEmpty());

        service.iniciar(GUILD, ALICE);
        assertFalse(service.status(GUILD, ALICE).isEmpty());
    }

    @Test
    void rankingSeparaConsolidadoEEmAndamento() {
        service.iniciar(GUILD, ALICE);
        clock.advance(min(40));
        service.iniciar(GUILD, BOB);
        clock.advance(min(10));
        service.encerrar(GUILD, BOB);
        clock.advance(min(5));

        List<PontoService.RankEntry> top = service.ranking(GUILD, 0);
        assertEquals(2, top.size());

        assertEquals(BOB, top.get(0).userId());
        assertEquals(min(10).toMillis(), top.get(0).consolidatedMs());
        assertEquals(0, top.get(0).runningMs());
        assertEquals(ALICE, top.get(1).userId());
        assertEquals(0, top.get(1).consolidatedMs());
        assertEquals(min(55).toMillis(), top.get(1).runningMs());
        assertEquals(min(55).toMillis(), top.get(1).totalMs());
    }

    @Test
    void rankingDeSemanaPassadaNaoTemEmAndamento() {
        clock.set(at("2026-11-07T22:00:00"));
        service.iniciar(GUILD, ALICE);
        clock.set(at("2026-11-08T01:00:00"));
        var prev = service.ranking(GUILD, 1).get(0);
        assertEquals(min(120).toMillis(), prev.consolidatedMs());
        assertEquals(0, prev.runningMs());
        var cur = service.ranking(GUILD, 0).get(0);
        assertEquals(0, cur.consolidatedMs());
        assertEquals(min(60).toMillis(), cur.runningMs());
    }

    @Test
    void rankingRetornaTodosOsUsuarios() {
        for (long u = 100; u < 115; u++) {
            service.iniciar(GUILD, u);
        }
        clock.advance(min(1));
        assertEquals(15, service.ranking(GUILD, 0).size());
    }

    @Test
    void sessaoAtravessandoDomingoEhRecortadaNoRanking() {
        clock.set(at("2026-11-07T22:00:00"));
        service.iniciar(GUILD, ALICE);
        clock.set(at("2026-11-08T01:00:00"));

        assertEquals(min(120).toMillis(), service.ranking(GUILD, 1).get(0).totalMs());
        assertEquals(min(60).toMillis(), service.ranking(GUILD, 0).get(0).totalMs());
    }

    @Test
    void rolloverMantemEstadoRunning() {
        clock.set(at("2026-11-07T22:00:00"));
        service.iniciar(GUILD, ALICE);
        long limit = at("2026-11-08T00:00:00").toEpochMilli();

        clock.set(at("2026-11-08T00:00:00"));
        assertEquals(1, service.rollover(limit));
        clock.set(at("2026-11-08T01:00:00"));

        var st = service.status(GUILD, ALICE).orElseThrow();
        assertEquals(State.RUNNING, st.state());
        assertEquals(min(60).toMillis(), st.sessionMs());
        assertEquals(min(60).toMillis(), st.weekMs());
        assertEquals(min(120).toMillis(), service.ranking(GUILD, 1).get(0).totalMs());
        assertEquals(2, sessionCount());

        clock.advance(min(30));
        assertEquals(min(90).toMillis(), service.encerrar(GUILD, ALICE).weekMs());
    }

    @Test
    void rolloverMantemEstadoPaused() {
        clock.set(at("2026-11-07T22:00:00"));
        service.iniciar(GUILD, ALICE);
        clock.set(at("2026-11-07T23:00:00"));
        service.pausar(GUILD, ALICE);
        long limit = at("2026-11-08T00:00:00").toEpochMilli();

        clock.set(at("2026-11-08T00:00:00"));
        assertEquals(1, service.rollover(limit));
        clock.set(at("2026-11-08T03:00:00"));

        var st = service.status(GUILD, ALICE).orElseThrow();
        assertEquals(State.PAUSED, st.state());
        assertEquals(0, st.sessionMs());
        assertEquals(0, st.weekMs());
        assertEquals(min(60).toMillis(), service.ranking(GUILD, 1).get(0).totalMs());

        service.retomar(GUILD, ALICE);
        clock.advance(min(20));
        assertEquals(min(20).toMillis(), service.status(GUILD, ALICE).orElseThrow().sessionMs());
    }

    @Test
    void rolloverEhIdempotente() {
        clock.set(at("2026-11-07T22:00:00"));
        service.iniciar(GUILD, ALICE);
        service.iniciar(GUILD, BOB);
        service.pausar(GUILD, BOB);
        long limit = at("2026-11-08T00:00:00").toEpochMilli();
        clock.set(at("2026-11-08T00:00:00"));

        assertEquals(2, service.rollover(limit));
        clock.advance(min(1));
        long sessions = sessionCount();
        long aliceWeek = service.ranking(GUILD, 0).get(0).totalMs();

        assertEquals(0, service.rollover(limit));
        assertEquals(sessions, sessionCount());
        assertEquals(aliceWeek, service.ranking(GUILD, 0).get(0).totalMs());
        assertEquals(State.RUNNING, service.status(GUILD, ALICE).orElseThrow().state());
    }

    @Test
    void rolloverIgnoraSessoesIniciadasDepoisDoLimite() {
        service.iniciar(GUILD, ALICE);
        assertEquals(0, service.rollover(at("2026-11-08T00:00:00").toEpochMilli()));
        assertEquals(1, sessionCount());
    }

    @Test
    void recuperacaoAposOfflineFechaNoUltimoHeartbeat(@TempDir Path dir) {
        String file = dir.resolve("ponto.db").toString();
        try (Database d1 = new Database(file)) {
            var s1 = new PontoService(d1, clock, ZONE);
            s1.iniciar(GUILD, ALICE);
            clock.advance(min(10));
            s1.heartbeat();
            clock.advance(min(5));
        }
        clock.advance(Duration.ofHours(3));

        try (Database d2 = new Database(file)) {
            var s2 = new PontoService(d2, clock, ZONE);
            assertEquals(1, s2.recoverFromHeartbeat());

            var st = s2.status(GUILD, ALICE).orElseThrow();
            assertEquals(State.PAUSED, st.state());
            assertEquals(min(10).toMillis(), st.sessionMs());

            s2.retomar(GUILD, ALICE);
            clock.advance(min(5));
            assertEquals(min(15).toMillis(), s2.status(GUILD, ALICE).orElseThrow().sessionMs());
        }
    }

    @Test
    void recuperacaoSemIntervalosAbertosNaoFazNada() {
        service.iniciar(GUILD, ALICE);
        service.pausar(GUILD, ALICE);
        service.heartbeat();
        assertEquals(0, service.recoverFromHeartbeat());
    }

    @Test
    void janelasDeSemana() {
        WeekUtil.Window w = WeekUtil.window(at("2026-11-10T12:00:00").toEpochMilli(), ZONE, 0);
        assertEquals(at("2026-11-08T00:00:00").toEpochMilli(), w.startMs());
        assertEquals(at("2026-11-15T00:00:00").toEpochMilli(), w.endMs());

        WeekUtil.Window sun = WeekUtil.window(at("2026-11-08T00:00:00").toEpochMilli(), ZONE, 0);
        assertEquals(at("2026-11-08T00:00:00").toEpochMilli(), sun.startMs());

        WeekUtil.Window prev = WeekUtil.window(at("2026-11-10T12:00:00").toEpochMilli(), ZONE, 1);
        assertEquals(at("2026-11-01T00:00:00").toEpochMilli(), prev.startMs());
        assertEquals(at("2026-11-08T00:00:00").toEpochMilli(), prev.endMs());

        assertEquals(at("2026-11-15T00:00:00").toEpochMilli(),
                WeekUtil.nextWeekStartMs(at("2026-11-08T00:00:00").toEpochMilli(), ZONE));
        assertEquals(at("2026-11-15T00:00:00").toEpochMilli(),
                WeekUtil.nextWeekStartMs(at("2026-11-14T23:59:59").toEpochMilli(), ZONE));
    }

    @Test
    void formatacaoDeTempo() {
        assertEquals("2h 15min 03s", TimeFormat.duration(Duration.ofSeconds(2 * 3600 + 15 * 60 + 3).toMillis()));
        assertEquals("15min 03s", TimeFormat.duration(Duration.ofSeconds(15 * 60 + 3).toMillis()));
        assertEquals("7s", TimeFormat.duration(7_000));
        assertEquals("0s", TimeFormat.duration(0));
    }
}
