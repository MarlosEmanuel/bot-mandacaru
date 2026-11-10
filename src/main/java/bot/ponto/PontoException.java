package bot.ponto;

/** Violação de regra de negócio; a mensagem pode ser mostrada ao usuário. */
public class PontoException extends RuntimeException {
    public PontoException(String message) {
        super(message);
    }
}
