package bot.ponto;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class PontoListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PontoListener.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    private record View(MessageEmbed embed, List<ActionRow> rows, String text) {
    }

    private final Config config;
    private final PontoService service;

    public PontoListener(Config config, PontoService service) {
        this.config = config;
        this.service = service;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getMember() == null) {
            event.reply("Use este comando em um servidor.").setEphemeral(true).queue();
            return;
        }
        if (!allowedGuild(event.getGuild().getIdLong())) {
            event.reply("Este bot não está configurado para este servidor.").setEphemeral(true).queue();
            return;
        }
        if (event.getName().equals("clear")) {
            handleClear(event);
            return;
        }
        var onlyIn = event.getName().equals("ponto") ? config.pontoChannelId() : config.rankingChannelId();
        if (onlyIn.isPresent() && onlyIn.getAsLong() != event.getChannelIdLong()) {
            event.reply("Use este comando em <#" + onlyIn.getAsLong() + ">.").setEphemeral(true).queue();
            return;
        }
        try {
            switch (event.getName()) {
                case "ponto" -> handlePonto(event);
                case "ranking" -> handleRanking(event);
                default -> { }
            }
        } catch (PontoException e) {
            event.reply(e.getMessage()).setEphemeral(true).queue();
        } catch (RuntimeException e) {
            log.error("Erro no comando /{}", event.getName(), e);
            event.reply("Erro interno. Tente novamente.").setEphemeral(true).queue();
        }
    }

    private void handlePonto(SlashCommandInteractionEvent event) {
        long guildId = event.getGuild().getIdLong();
        Member member = event.getMember();
        long userId = member.getIdLong();

        var st = service.status(guildId, userId);
        if (st.isEmpty()) {
            requireInCall(member);
            service.iniciar(guildId, userId);
            st = service.status(guildId, userId);
        }
        var status = st.orElseThrow();
        event.replyEmbeds(PontoPanel.open(userId, status))
                .setComponents(PontoPanel.buttons(userId, status.state()))
                .queue(hook -> hook.retrieveOriginal().queue(msg ->
                        service.savePanel(guildId, userId, msg.getChannelIdLong(), msg.getIdLong())));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        if (parts.length == 5 && parts[0].equals(RankingPanel.PREFIX)
                && event.getGuild() != null && allowedGuild(event.getGuild().getIdLong())) {
            handleRankingButton(event, parts);
            return;
        }
        if (parts.length != 3 || !parts[0].equals(PontoPanel.PREFIX)
                || event.getGuild() == null || event.getMember() == null) {
            return;
        }
        long ownerId;
        try {
            ownerId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }
        if (ownerId != event.getUser().getIdLong()) {
            event.reply("Só quem abriu o ponto pode usar estes botões.").setEphemeral(true).queue();
            return;
        }
        long guildId = event.getGuild().getIdLong();
        if (!allowedGuild(guildId)) {
            return;
        }
        try {
            View v = perform(parts[1], guildId, event.getMember());
            event.editMessageEmbeds(v.embed()).setComponents(v.rows()).queue();
            if (parts[1].equals(PontoPanel.END)) {
                service.clearPanel(guildId, ownerId);
            } else {
                service.savePanel(guildId, ownerId, event.getChannelIdLong(), event.getMessageIdLong());
            }
        } catch (PontoException e) {
            event.reply(e.getMessage()).setEphemeral(true).queue();
        } catch (RuntimeException e) {
            log.error("Erro no botão {}", event.getComponentId(), e);
            event.reply("Erro interno. Tente novamente.").setEphemeral(true).queue();
        }
    }

    private View perform(String action, long guildId, Member member) {
        long userId = member.getIdLong();
        switch (action) {
            case PontoPanel.PAUSE -> {
                service.pausar(guildId, userId);
                return openView(guildId, userId, "Ponto pausado.");
            }
            case PontoPanel.RESUME -> {
                requireInCall(member);
                service.retomar(guildId, userId);
                return openView(guildId, userId, "Ponto retomado.");
            }
            case PontoPanel.END -> {
                var sum = service.encerrar(guildId, userId);
                return new View(PontoPanel.closed(userId, sum), List.of(),
                        "Ponto encerrado. Tempo da sessão: " + TimeFormat.duration(sum.sessionMs())
                                + " · Total da semana: " + TimeFormat.duration(sum.weekMs()));
            }
            default -> throw new PontoException("Ação desconhecida.");
        }
    }

    private View openView(long guildId, long userId, String text) {
        var st = service.status(guildId, userId).orElseThrow();
        return new View(PontoPanel.open(userId, st), PontoPanel.buttons(userId, st.state()), text);
    }
    private void refreshPanel(JDA jda, long guildId, long userId, View v) {
        var found = service.findPanel(guildId, userId);
        if (found.isEmpty()) {
            log.warn("Sem painel registrado para {} (nada a atualizar)", userId);
            return;
        }
        PontoService.PanelRef ref = found.get();
        if (v.rows().isEmpty()) {
            service.clearPanel(guildId, userId);
        }
        MessageChannel ch = jda.getChannelById(MessageChannel.class, ref.channelId());
        if (ch == null) {
            log.warn("Canal {} do painel não encontrado", ref.channelId());
            return;
        }
        try {
            ch.editMessageEmbedsById(ref.messageId(), v.embed()).setComponents(v.rows())
                    .queue(ok -> { },
                            err -> log.warn("Não consegui atualizar o painel de {}: {}", userId, err.toString()));
        } catch (InsufficientPermissionException e) {
            log.warn("O bot não tem a permissão {} no canal {}; painel de {} não foi atualizado. "
                    + "Dê ao bot: Ver canal, Enviar mensagens, Inserir links e Ver histórico de mensagens.",
                    e.getPermission(), ref.channelId(), userId);
        }
    }

    private void handleClear(SlashCommandInteractionEvent event) {
        if (!event.getMember().hasPermission(event.getGuildChannel(), Permission.MESSAGE_MANAGE)) {
            event.reply("Você precisa da permissão Gerenciar mensagens.").setEphemeral(true).queue();
            return;
        }
        var channel = event.getChannel();
        OptionMapping opt = event.getOption("quantidade");
        long limit = opt == null ? Long.MAX_VALUE : opt.getAsLong();
        event.deferReply(true).queue();
        var cutoff = java.time.OffsetDateTime.now().minusDays(14).plusMinutes(5);
        java.util.List<net.dv8tion.jda.api.entities.Message> toDelete = new java.util.ArrayList<>();
        channel.getIterableHistory().forEachAsync(m -> {
            if (m.getTimeCreated().isBefore(cutoff)) {
                return false;
            }
            toDelete.add(m);
            return toDelete.size() < limit;
        }).whenComplete((ok, err) -> {
            if (err != null) {
                log.error("Erro ao ler histórico para /clear", err);
                event.getHook().editOriginal("Não consegui ler as mensagens do canal. Verifique as permissões do bot.").queue();
                return;
            }
            if (toDelete.isEmpty()) {
                event.getHook().editOriginal("Não há mensagens para apagar.").queue();
                return;
            }
            var futures = channel.purgeMessages(toDelete);
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            log.error("Erro ao apagar mensagens", e);
                            event.getHook().editOriginal("Erro ao apagar algumas mensagens.").queue();
                        } else {
                            event.getHook().editOriginal("🧹 " + toDelete.size() + " mensagens apagadas.").queue();
                        }
                    });
        });
    }

    private void handleRanking(SlashCommandInteractionEvent event) {
        OptionMapping opt = event.getOption("semanas_atras");
        int weeksAgo = opt == null ? 0 : Math.max(0, opt.getAsInt());
        long userId = event.getUser().getIdLong();
        var built = rankingView(event.getGuild().getIdLong(), userId, weeksAgo, 0);
        event.replyEmbeds(built.embed()).setComponents(built.rows()).queue();
    }

    private RankingPanel.Built rankingView(long guildId, long requesterId, int weeksAgo, int page) {
        return RankingPanel.build(service.ranking(guildId, weeksAgo), requesterId, weeksAgo, page, weekRange(weeksAgo));
    }

    private String weekRange(int weeksAgo) {
        WeekUtil.Window w = service.week(weeksAgo);
        ZoneId zone = config.zone();
        return DAY.format(Instant.ofEpochMilli(w.startMs()).atZone(zone))
                + " a " + DAY.format(Instant.ofEpochMilli(w.endMs() - 1).atZone(zone));
    }

    private void handleRankingButton(ButtonInteractionEvent event, String[] parts) {
        String action = parts[1];
        int weeksAgo;
        int page;
        long ownerId;
        try {
            weeksAgo = Integer.parseInt(parts[2]);
            page = Integer.parseInt(parts[3]);
            ownerId = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            return;
        }
        if (ownerId != event.getUser().getIdLong()) {
            event.reply("Só quem usou `/ranking` pode usar estes botões. Digite `/ranking` para ver o seu.")
                    .setEphemeral(true).queue();
            return;
        }
        long guildId = event.getGuild().getIdLong();
        try {
            if (action.equals(RankingPanel.EXPORT)) {
                String csv = RankingPanel.csv(service.ranking(guildId, weeksAgo));
                event.replyFiles(FileUpload.fromData(csv.getBytes(StandardCharsets.UTF_8), "ranking.csv"))
                        .setEphemeral(true).queue();
                return;
            }
            int last = RankingPanel.pageCount(service.ranking(guildId, weeksAgo).size()) - 1;
            int target = switch (action) {
                case RankingPanel.FIRST -> 0;
                case RankingPanel.PREV -> page - 1;
                case RankingPanel.NEXT -> page + 1;
                case RankingPanel.LAST -> last;
                default -> page;
            };
            var built = rankingView(guildId, ownerId, weeksAgo, target);
            event.editMessageEmbeds(built.embed()).setComponents(built.rows()).queue();
        } catch (RuntimeException e) {
            log.error("Erro no botão {}", event.getComponentId(), e);
            event.reply("Erro interno. Tente novamente.").setEphemeral(true).queue();
        }
    }

    private boolean allowedGuild(long guildId) {
        return config.guildId().isEmpty() || config.guildId().getAsLong() == guildId;
    }

    private void requireInCall(Member member) {
        var vs = member.getVoiceState();
        AudioChannelUnion ch = vs == null ? null : vs.getChannel();
        if (ch == null || ch.getIdLong() != config.voiceChannelId()) {
            throw new PontoException("Você precisa estar na call de voz <#" + config.voiceChannelId() + "> para isso.");
        }
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (!config.autoEndOnLeave()) {
            return;
        }
        var left = event.getChannelLeft();
        if (left == null || left.getIdLong() != config.voiceChannelId()) {
            return;
        }
        var joined = event.getChannelJoined();
        if (joined != null && joined.getIdLong() == config.voiceChannelId()) {
            return;
        }
        Member m = event.getMember();
        if (m.getUser().isBot()) {
            return;
        }
        long guildId = event.getGuild().getIdLong();
        try {
            service.encerrarSeAberto(guildId, m.getIdLong()).ifPresent(sum -> {
                log.info("Ponto de {} encerrado automaticamente (saiu da call): sessão {}",
                        m.getId(), TimeFormat.duration(sum.sessionMs()));
                refreshPanel(event.getJDA(), guildId, m.getIdLong(),
                        new View(PontoPanel.closed(m.getIdLong(), sum), List.of(), ""));
            });
        } catch (RuntimeException e) {
            log.error("Falha ao encerrar automaticamente {}", m.getId(), e);
        }
    }
}
