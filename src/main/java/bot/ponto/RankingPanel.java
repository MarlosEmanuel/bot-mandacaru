package bot.ponto;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.util.List;

final class RankingPanel {

    static final String PREFIX = "rk";
    static final String FIRST = "first";
    static final String PREV = "prev";
    static final String NEXT = "next";
    static final String LAST = "last";
    static final String EXPORT = "export";
    static final int PAGE_SIZE = 10;

    record Built(MessageEmbed embed, List<ActionRow> rows) {
    }

    private RankingPanel() {
    }

    static int pageCount(int entries) {
        return Math.max(1, (entries + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    static String id(String action, int weeksAgo, int page, long requesterId) {
        return PREFIX + ":" + action + ":" + weeksAgo + ":" + page + ":" + requesterId;
    }

    static Built build(List<PontoService.RankEntry> all, long requesterId, int weeksAgo, int page, String range) {
        int pages = pageCount(all.size());
        page = Math.min(Math.max(0, page), pages - 1);

        StringBuilder sb = new StringBuilder("**🏆 Ranking de Tempo • Total (consolidado)**\n")
                .append("Semana: ").append(range).append("\n\n");
        if (all.isEmpty()) {
            sb.append("Ninguém registrou tempo nesta semana.");
        }
        int from = page * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            PontoService.RankEntry e = all.get(i);
            sb.append(i + 1).append("º <@").append(e.userId()).append("> • ")
                    .append(TimeFormat.compact(e.consolidatedMs()));
            if (e.runningMs() > 0) {
                sb.append(" (+").append(TimeFormat.compact(e.runningMs())).append(" em andamento) 🟢");
            }
            sb.append('\n');
        }

        sb.append('\n').append(position(all, requesterId));

        String footer = "Página " + (page + 1) + "/" + pages + " • Total: " + all.size()
                + (all.size() == 1 ? " usuário" : " usuários");
        MessageEmbed embed = new EmbedBuilder()
                .setTitle("Bate-Ponto • Ranking")
                .setColor(new Color(0xF0B232))
                .setDescription(sb.toString().stripTrailing())
                .setFooter(footer)
                .build();

        boolean atStart = page == 0;
        boolean atEnd = page == pages - 1;
        List<ActionRow> rows = List.of(ActionRow.of(
                Button.secondary(id(FIRST, weeksAgo, page, requesterId), "«").withDisabled(atStart),
                Button.secondary(id(PREV, weeksAgo, page, requesterId), "‹").withDisabled(atStart),
                Button.secondary(id(NEXT, weeksAgo, page, requesterId), "›").withDisabled(atEnd),
                Button.secondary(id(LAST, weeksAgo, page, requesterId), "»").withDisabled(atEnd),
                Button.success(id(EXPORT, weeksAgo, page, requesterId), "Exportar")));
        return new Built(embed, rows);
    }

    private static String position(List<PontoService.RankEntry> all, long requesterId) {
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).userId() == requesterId) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return "📍 **Sua posição:** você ainda não registrou tempo nesta semana";
        }
        PontoService.RankEntry me = all.get(idx);
        StringBuilder sb = new StringBuilder("📍 **Sua posição:** ")
                .append(idx + 1).append("º de ").append(all.size())
                .append(" • ").append(TimeFormat.compact(me.consolidatedMs()));
        if (idx > 0) {
            long diff = all.get(idx - 1).consolidatedMs() - me.consolidatedMs();
            sb.append(" • faltam ")
                    .append(diff < 60_000 ? "menos de 1m" : TimeFormat.compact(diff))
                    .append(" para o ").append(idx).append("º");
        }
        return sb.toString();
    }

    static String csv(List<PontoService.RankEntry> all) {
        StringBuilder sb = new StringBuilder("posicao,usuario_id,consolidado,em_andamento\n");
        for (int i = 0; i < all.size(); i++) {
            PontoService.RankEntry e = all.get(i);
            sb.append(i + 1).append(',').append(e.userId()).append(',')
                    .append(TimeFormat.duration(e.consolidatedMs())).append(',')
                    .append(TimeFormat.duration(e.runningMs())).append('\n');
        }
        return sb.toString();
    }
}
