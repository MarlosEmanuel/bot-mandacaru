package bot.ponto;

import bot.ponto.PontoService.RankEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingPanelTest {

    static final long MIN = 60_000;

    static List<RankEntry> entries(int n) {
        List<RankEntry> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new RankEntry(1000 + i, (n - i) * 60 * MIN, 0)); // decrescente
        }
        return l;
    }

    @Test
    void paginaDoisMostraPosicoesOnzeEmDiante() {
        var all = entries(26);
        var built = RankingPanel.build(all, 1010, 0, 1, "08/11 a 14/11"); // usuário na 11ª posição
        String d = built.embed().getDescription();
        assertTrue(d.contains("11º <@1010>"));
        assertTrue(d.contains("20º <@1019>"));
        assertTrue(!d.contains("21º"));
        assertTrue(d.contains("**Sua posição:** 11º de 26 • 16h 0m • faltam 1h 0m para o 10º"));
        assertEquals("Página 2/3 • Total: 26 usuários", built.embed().getFooter().getText());
    }

    @Test
    void emAndamentoMostraIndicador() {
        var all = List.of(new RankEntry(1, 7 * 60 * MIN + 3 * MIN, 94 * MIN));
        String d = RankingPanel.build(all, 1, 0, 0, "x").embed().getDescription();
        assertTrue(d.contains("1º <@1> • 7h 3m (+1h 34m em andamento) 🟢"));
    }

    @Test
    void usuarioForaDoRankingEPaginaVazia() {
        String d = RankingPanel.build(List.of(), 5, 0, 0, "x").embed().getDescription();
        assertTrue(d.contains("Ninguém registrou tempo"));
        assertTrue(d.contains("você ainda não registrou tempo"));
        assertEquals(1, RankingPanel.pageCount(0));
        assertEquals(3, RankingPanel.pageCount(26));
    }

    @Test
    void paginaForaDoLimiteEhAjustada() {
        var built = RankingPanel.build(entries(26), 1, 0, 99, "x");
        assertEquals("Página 3/3 • Total: 26 usuários", built.embed().getFooter().getText());
    }

    @Test
    void csvTemCabecalhoELinhas() {
        String csv = RankingPanel.csv(entries(2));
        assertEquals(3, csv.lines().count());
        assertTrue(csv.startsWith("posicao,usuario_id,consolidado,em_andamento"));
    }
}
