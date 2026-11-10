package bot.ponto;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        Config config;
        try {
            config = Config.fromEnv();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        Clock clock = Clock.systemUTC();
        Database db = new Database(config.dbPath());
        PontoService service = new PontoService(db, clock, config.zone());

        // Recuperação após queda: fecha intervalos abertos no último heartbeat e pausa as sessões.
        int recovered = service.recoverFromHeartbeat();
        if (recovered > 0) {
            log.warn("{} sessão(ões) pausada(s) após reinício (tempo offline não contado)", recovered);
        }
        service.heartbeat();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ponto-scheduler");
            t.setDaemon(true);
            return t;
        });
        new Rollover(service, clock, config.zone(), scheduler).start();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                service.heartbeat();
            } catch (RuntimeException e) {
                log.error("Falha ao gravar heartbeat", e);
            }
        }, 60, 60, TimeUnit.SECONDS);

        JDABuilder builder = JDABuilder.createLight(config.token(), GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .addEventListeners(new PontoListener(config, service));
        if (config.welcomeChannelId().isPresent()) {
            // intent privilegiado: ative "Server Members Intent" no Developer Portal
            builder.enableIntents(GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(new WelcomeListener(config));
        }
        JDA jda = builder.build().awaitReady();

        List<CommandData> commands = commands();
        if (config.guildId().isPresent()) {
            Guild guild = jda.getGuildById(config.guildId().getAsLong());
            if (guild == null) {
                log.error("GUILD_ID {} não encontrado (o bot está no servidor?)", config.guildId().getAsLong());
                System.exit(1);
                return;
            }
            guild.updateCommands().addCommands(commands).queue(); // aparece na hora
        } else {
            jda.updateCommands().addCommands(commands).queue();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                service.heartbeat(); // marca o instante da parada para a recuperação
            } catch (RuntimeException ignored) {
                // melhor esforço
            }
            scheduler.shutdownNow();
            jda.shutdown();
            db.close();
        }));
        log.info("Bot pronto.");
    }

    private static List<CommandData> commands() {
        return List.of(
                Commands.slash("ponto", "Inicia o ponto (você precisa estar na call)")
                        .setContexts(InteractionContextType.GUILD),
                Commands.slash("ranking", "Top 10 da semana")
                        .setContexts(InteractionContextType.GUILD)
                        .addOptions(new OptionData(OptionType.INTEGER, "semanas_atras",
                                "Quantas semanas atrás (0 = semana atual)", false).setMinValue(0)));
    }
}
