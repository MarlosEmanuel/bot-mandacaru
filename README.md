# Bot de ponto por call de voz

Bot de Discord (JDA 6.7.0, Java 21, SQLite) que registra tempo de trabalho na call e mostra um ranking semanal.

## Comandos

| Comando | O que faz |
|---|---|
| `/ponto` | Inicia o ponto (precisa estar na call) e abre o painel. Se você já tem ponto aberto, reenvia o painel atual |
| `/ranking [semanas_atras]` | Ranking da semana (público), paginado de 10 em 10 com botões « ‹ › » e **Exportar** (CSV). Mostra "Sua posição" e quem está com ponto rodando (🟢). A semana vai de domingo 00:00 a domingo 00:00. Ordena pelo tempo consolidado (intervalos fechados) |

Pausar, retomar e terminar são feitos pelos botões do painel (**Pausar/Retomar** e **Terminar**). Retomar exige estar na call.

Sair da call com o ponto aberto (rodando ou pausado) encerra o ponto automaticamente (`AUTO_END_ON_LEAVE`).

## Criar o bot no Developer Portal

1. https://discord.com/developers/applications → **New Application**.
2. Aba **Bot** → **Reset Token** e copie o token. Não ative nenhum Privileged Intent.
3. **OAuth2 → URL Generator**: scopes `bot` e `applications.commands`. Permissões: nenhuma é necessária.
4. Abra a URL gerada e adicione o bot ao servidor.
5. No Discord, ative o Modo Desenvolvedor e copie o ID da call (VOICE_CHANNEL_ID) e do servidor (GUILD_ID).

## Variáveis de ambiente

| Variável | Obrigatória | Padrão |
|---|---|---|
| `DISCORD_TOKEN` | sim | |
| `VOICE_CHANNEL_ID` | sim | |
| `GUILD_ID` | não | se definido, comandos são registrados só nesse servidor (aparecem na hora) |
| `PONTO_CHANNEL_ID` | não | se definido, `/ponto` só funciona nesse canal de texto |
| `RANKING_CHANNEL_ID` | não | se definido, `/ranking` só funciona nesse canal de texto |
| `WELCOME_CHANNEL_ID` | não | se definido, envia o embed "Boas Vindas!" (com avatar e contagem de membros) nesse canal quando alguém entra. **Exige ativar "Server Members Intent" no Developer Portal (Bot → Privileged Gateway Intents)** |
| `TIMEZONE` | não | `America/Fortaleza` |
| `AUTO_END_ON_LEAVE` | não | `true` |
| `DB_PATH` | não | `./ponto.db` |

## Build e execução

```bash
mvn package
DISCORD_TOKEN=... VOICE_CHANNEL_ID=... java -jar target/bot-ponto.jar
```

## Serviço systemd

`/etc/systemd/system/bot-ponto.service`:

```ini
[Unit]
Description=Bot de ponto (Discord)
After=network-online.target
Wants=network-online.target

[Service]
User=botponto
WorkingDirectory=/opt/bot-ponto
EnvironmentFile=/opt/bot-ponto/.env
ExecStart=/usr/bin/java -Xmx256m -jar /opt/bot-ponto/bot-ponto.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`/opt/bot-ponto/.env` (permissão `600`):

```
DISCORD_TOKEN=...
VOICE_CHANNEL_ID=...
GUILD_ID=...
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now bot-ponto
```

## Como funciona

- Cada sessão guarda **intervalos** (`intervals`); o tempo é sempre a soma deles. Não há contador nem tabela de ranking.
- Todo domingo 00:00 (na `TIMEZONE`) sessões abertas são fechadas exatamente nesse instante e reabertas no mesmo estado, sem mensagens.
- A cada 60 s o bot grava um heartbeat. Se cair, ao subir ele fecha os intervalos abertos no último heartbeat e pausa essas sessões: o tempo offline não conta, e a pessoa usa `/ponto retomar`.
