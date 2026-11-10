package bot.ponto;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;

public final class WelcomeListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(WelcomeListener.class);

    private final Config config;

    public WelcomeListener(Config config) {
        this.config = config;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (config.welcomeChannelId().isEmpty() || event.getUser().isBot()) {
            return;
        }
        if (config.guildId().isPresent() && config.guildId().getAsLong() != event.getGuild().getIdLong()) {
            return;
        }
        TextChannel channel = event.getGuild().getTextChannelById(config.welcomeChannelId().getAsLong());
        if (channel == null) {
            log.warn("Canal de boas-vindas {} não encontrado", config.welcomeChannelId().getAsLong());
            return;
        }
        var member = event.getMember();
        var guild = event.getGuild();
        MessageEmbed embed = new EmbedBuilder()
                .setTitle("👋 Boas Vindas!")
                .setColor(new Color(0x2ECC71))
                .setDescription("> Olá " + member.getAsMention() + "!\n\n"
                        + "Seja Bem-Vindo(a) ao `" + guild.getName() + "`!\n"
                        + "Atualmente estamos com `" + guild.getMemberCount() + "` membros.")
                .setThumbnail(member.getEffectiveAvatarUrl())
                .build();

        channel.sendMessage(member.getAsMention()).setEmbeds(embed).queue(ok -> { },
                err -> log.warn("Não consegui enviar a boas-vindas: {}", err.getMessage()));
    }
}
