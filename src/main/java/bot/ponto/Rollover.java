package bot.ponto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Rollover {

    private static final Logger log = LoggerFactory.getLogger(Rollover.class);

    private final PontoService service;
    private final Clock clock;
    private final ZoneId zone;
    private final ScheduledExecutorService executor;

    public Rollover(PontoService service, Clock clock, ZoneId zone, ScheduledExecutorService executor) {
        this.service = service;
        this.clock = clock;
        this.zone = zone;
        this.executor = executor;
    }

    public void start() {
        int n = service.rollover(WeekUtil.window(clock.millis(), zone, 0).startMs());
        if (n > 0) {
            log.info("Rollover de inicialização: {} sessão(ões) viradas", n);
        }
        scheduleNext();
    }

    private void scheduleNext() {
        long now = clock.millis();
        long target = WeekUtil.nextWeekStartMs(now, zone);
        executor.schedule(() -> fire(target), Math.max(0, target - now), TimeUnit.MILLISECONDS);
    }

    private void fire(long target) {
        if (clock.millis() < target) {
            scheduleNext();
            return;
        }
        try {
            int n = service.rollover(target);
            log.info("Virada de semana: {} sessão(ões) viradas", n);
        } catch (RuntimeException e) {
            log.error("Falha no rollover; nova tentativa em 30s", e);
            executor.schedule(() -> fire(target), 30, TimeUnit.SECONDS);
            return;
        }
        scheduleNext();
    }
}
