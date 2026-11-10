package bot.ponto;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.OptionalLong;
import java.util.function.Function;

/** Configuração lida de variáveis de ambiente. */
public record Config(
        String token,
        long voiceChannelId,
        OptionalLong guildId,
        OptionalLong pontoChannelId,
        OptionalLong rankingChannelId,
        OptionalLong welcomeChannelId,
        ZoneId zone,
        boolean autoEndOnLeave,
        String dbPath) {

    public static Config fromEnv() {
        return fromEnv(System::getenv);
    }

    public static Config fromEnv(Function<String, String> env) {
        String token = required(env, "DISCORD_TOKEN");
        long voiceChannelId = parseLong("VOICE_CHANNEL_ID", required(env, "VOICE_CHANNEL_ID"));

        String guildRaw = blankToNull(env.apply("GUILD_ID"));
        OptionalLong guildId = guildRaw == null
                ? OptionalLong.empty()
                : OptionalLong.of(parseLong("GUILD_ID", guildRaw));

        OptionalLong pontoChannel = optionalId(env, "PONTO_CHANNEL_ID");
        OptionalLong rankingChannel = optionalId(env, "RANKING_CHANNEL_ID");

        OptionalLong welcomeChannel = optionalId(env, "WELCOME_CHANNEL_ID");

        String tz = blankToNull(env.apply("TIMEZONE"));
        ZoneId zone;
        try {
            zone = ZoneId.of(tz == null ? "America/Fortaleza" : tz);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("TIMEZONE inválido: " + tz, e);
        }

        String auto = blankToNull(env.apply("AUTO_END_ON_LEAVE"));
        boolean autoEnd = auto == null || Boolean.parseBoolean(auto.trim());

        String db = blankToNull(env.apply("DB_PATH"));
        return new Config(token, voiceChannelId, guildId, pontoChannel, rankingChannel, welcomeChannel, zone, autoEnd, db == null ? "./ponto.db" : db);
    }

    private static OptionalLong optionalId(Function<String, String> env, String name) {
        String raw = blankToNull(env.apply(name));
        return raw == null ? OptionalLong.empty() : OptionalLong.of(parseLong(name, raw));
    }

    private static String required(Function<String, String> env, String name) {
        String v = blankToNull(env.apply(name));
        if (v == null) {
            throw new IllegalArgumentException("Variável de ambiente obrigatória não definida: " + name);
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long parseLong(String name, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " deve ser um número: " + value, e);
        }
    }
}
