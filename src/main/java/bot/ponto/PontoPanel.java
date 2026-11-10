package bot.ponto;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.util.List;

/** Monta o painel "Bate-Ponto" (embed + botões). */
final class PontoPanel {

    static final String PREFIX = "ponto";
    static final String PAUSE = "pause";
    static final String RESUME = "resume";
    static final String END = "end";

    private static final String FOOTER = "Se você sair da call, o ponto será encerrado automaticamente.";

    private PontoPanel() {
    }

    static String buttonId(String action, long userId) {
        return PREFIX + ":" + action + ":" + userId;
    }

    /** Painel de um ponto aberto (rodando ou pausado). */
    static MessageEmbed open(long userId, PontoService.Status s) {
        boolean running = s.state() == PontoService.State.RUNNING;
        return new EmbedBuilder()
                .setTitle("Bate-Ponto")
                .setColor(running ? new Color(0xF0A030) : new Color(0x95A5A6))
                .addField("Usuário", "<@" + userId + ">", true)
                .addField("Início", stamp(s.startedAt()), true)
                .addField("Status", running ? "⏳ Em andamento" : "⏸️ Pausado", false)
                .setFooter(FOOTER)
                .build();
    }

    /** Painel de um ponto encerrado. */
    static MessageEmbed closed(long userId, PontoService.Summary s) {
        return new EmbedBuilder()
                .setTitle("Bate-Ponto")
                .setColor(new Color(0x2ECC71))
                .addField("Usuário", "<@" + userId + ">", true)
                .addField("Início", stamp(s.startedAt()), true)
                .addField("Término", "<t:" + sec(s.endedAt()) + ":t>", true)
                .addField("Tempo total", TimeFormat.duration(s.sessionMs()), false)
                .addField("Total Consolidado", TimeFormat.duration(s.weekMs()), false)
                .addField("Status", "✅ Encerrado", false)
                .setFooter(FOOTER)
                .build();
    }

    /** Botões do painel aberto; ponto encerrado não tem botões. */
    static List<ActionRow> buttons(long userId, PontoService.State state) {
        if (state == PontoService.State.CLOSED) {
            return List.of();
        }
        Button toggle = state == PontoService.State.RUNNING
                ? Button.secondary(buttonId(PAUSE, userId), "Pausar")
                : Button.success(buttonId(RESUME, userId), "Retomar");
        Button end = Button.danger(buttonId(END, userId), "Terminar");
        return List.of(ActionRow.of(toggle, end));
    }

    private static String stamp(long ms) {
        return "<t:" + sec(ms) + ":t> • <t:" + sec(ms) + ":R>";
    }

    private static long sec(long ms) {
        return ms / 1000;
    }
}
